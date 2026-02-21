package com.lemonhall.jediterm.android

import com.jediterm.core.typeahead.TerminalTypeAheadManager
import com.jediterm.core.typeahead.TypeAheadTerminalModel
import com.jediterm.core.typeahead.TypeAheadTerminalModel.LineWithCursorX
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalStarter
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalModelListener
import com.jediterm.terminal.model.TerminalTextBuffer
import java.util.concurrent.atomic.AtomicBoolean

class TerminalSessionManager(
  columns: Int = 80,
  rows: Int = 24,
  private val executorServiceManager: AndroidExecutorServiceManager = AndroidExecutorServiceManager(),
  display: ComposeTerminalDisplay? = null,
) {
  val display: ComposeTerminalDisplay = display ?: ComposeTerminalDisplay(executorServiceManager::runOnUiThread)
  private val styleState = StyleState().apply {
    val defaultStyle = TextStyle(TerminalColor.index(15), TerminalColor.index(0))
    setDefaultStyle(defaultStyle)
    setCurrent(defaultStyle)
  }
  val terminalTextBuffer: TerminalTextBuffer = TerminalTextBuffer(columns, rows, styleState)
  val terminal: JediTerminal = JediTerminal(this.display, terminalTextBuffer, styleState)

  private val started = AtomicBoolean(false)
  private var terminalStarter: TerminalStarter? = null

  fun addModelListener(listener: TerminalModelListener) {
    terminalTextBuffer.addModelListener(listener)
  }

  fun removeModelListener(listener: TerminalModelListener) {
    terminalTextBuffer.removeModelListener(listener)
  }

  fun startSession(ttyConnector: TtyConnector) {
    if (!started.compareAndSet(false, true)) return

    val dataStream = TtyBasedArrayDataStream(ttyConnector)
    val typeAheadManager = TerminalTypeAheadManager(DisabledTypeAheadTerminalModel())

    terminalStarter = TerminalStarter(
      terminal,
      ttyConnector,
      dataStream,
      typeAheadManager,
      executorServiceManager,
    ).also { starter ->
      executorServiceManager.getUnboundedExecutorService().execute { starter.start() }
      starter.postResize(TermSize(terminal.terminalWidth, terminal.terminalHeight), RequestOrigin.User)
    }
  }

  fun stopSession() {
    val starter = terminalStarter ?: return
    starter.requestEmulatorStop()
    starter.close()
    executorServiceManager.shutdownWhenAllExecuted()
  }

  fun sendString(text: String, userInput: Boolean = true) {
    terminalStarter?.sendString(text, userInput)
  }

  fun sendBytes(bytes: ByteArray, userInput: Boolean = true) {
    terminalStarter?.sendBytes(bytes, userInput)
  }

  fun resize(columns: Int, rows: Int, origin: RequestOrigin = RequestOrigin.User) {
    terminalStarter?.postResize(TermSize(columns, rows), origin)
  }

  private class DisabledTypeAheadTerminalModel : TypeAheadTerminalModel {
    override fun insertCharacter(ch: Char, index: Int) = Unit
    override fun removeCharacters(from: Int, count: Int) = Unit
    override fun moveCursor(index: Int) = Unit
    override fun forceRedraw() = Unit
    override fun clearPredictions() = Unit
    override fun lock() = Unit
    override fun unlock() = Unit
    override fun isUsingAlternateBuffer(): Boolean = false
    override fun getCurrentLineWithCursor(): LineWithCursorX = LineWithCursorX(StringBuffer(), 0)
    override fun getTerminalWidth(): Int = 0
    override fun isTypeAheadEnabled(): Boolean = false
    override fun getLatencyThreshold(): Long = Long.MAX_VALUE
    override fun getShellType(): TypeAheadTerminalModel.ShellType = TypeAheadTerminalModel.ShellType.Unknown
  }
}
