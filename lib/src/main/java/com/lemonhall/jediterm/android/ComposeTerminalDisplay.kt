package com.lemonhall.jediterm.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.TerminalSelection

class ComposeTerminalDisplay : TerminalDisplay {
  var cursorX by mutableIntStateOf(0)
    private set
  var cursorY by mutableIntStateOf(0)
    private set
  private var cursorVisibleState by mutableStateOf(true)
  private var cursorShapeState: CursorShape? by mutableStateOf(null)

  val cursorVisible: Boolean
    get() = cursorVisibleState

  val cursorShape: CursorShape?
    get() = cursorShapeState

  private var windowTitleState by mutableStateOf("")
  private var selectionState: TerminalSelection? by mutableStateOf(null)

  override fun setCursor(x: Int, y: Int) {
    cursorX = x
    cursorY = y
  }

  override fun setCursorShape(cursorShape: CursorShape?) {
    cursorShapeState = cursorShape
  }

  override fun beep() = Unit

  override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) = Unit

  override fun setCursorVisible(isCursorVisible: Boolean) {
    cursorVisibleState = isCursorVisible
  }

  override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) = Unit

  override fun getWindowTitle(): String = windowTitleState

  override fun setWindowTitle(windowTitle: String) {
    windowTitleState = windowTitle
  }

  override fun getSelection(): TerminalSelection? = selectionState

  override fun terminalMouseModeSet(mouseMode: MouseMode) = Unit

  override fun setMouseFormat(mouseFormat: MouseFormat) = Unit

  override fun ambiguousCharsAreDoubleWidth(): Boolean = false
}
