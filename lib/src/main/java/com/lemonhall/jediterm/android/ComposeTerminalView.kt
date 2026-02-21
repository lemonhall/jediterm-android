package com.lemonhall.jediterm.android

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.viewinterop.AndroidView
import com.jediterm.core.util.Ascii
import com.jediterm.core.input.InputEvent
import com.jediterm.core.input.KeyEvent as JediKeyEvent
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.model.TerminalModelListener
import com.jediterm.terminal.util.CharUtils
import kotlin.math.floor

@Composable
fun ComposeTerminalView(
  ttyConnector: TtyConnector,
  modifier: Modifier = Modifier,
  columns: Int = 80,
  rows: Int = 24,
  onResize: ((columns: Int, rows: Int) -> Unit)? = null,
) {
  val logTag = "JeditermIme"
  val executorServiceManager = remember { AndroidExecutorServiceManager() }
  val session = remember { TerminalSessionManager(columns = columns, rows = rows, executorServiceManager = executorServiceManager) }
  var bufferVersion by remember { mutableIntStateOf(0) }
  var inputViewRef by remember { mutableStateOf<TerminalInputView?>(null) }

  DisposableEffect(session) {
    val listener = TerminalModelListener {
      executorServiceManager.runOnUiThread { bufferVersion++ }
    }
    session.addModelListener(listener)
    onDispose { session.removeModelListener(listener) }
  }

  LaunchedEffect(session, ttyConnector) {
    session.startSession(ttyConnector)
  }

  DisposableEffect(session) {
    onDispose { session.stopSession() }
  }

  val textMeasurer = rememberTextMeasurer()
  val baseTextStyle = remember {
    TextStyle(
      fontFamily = FontFamily.Monospace,
      fontSize = 13.sp,
    )
  }
  val charLayout = remember(textMeasurer, baseTextStyle) {
    textMeasurer.measure(text = "M", style = baseTextStyle)
  }
  val charWidthPx = charLayout.size.width.toFloat().coerceAtLeast(1f)
  val charHeightPx = charLayout.size.height.toFloat().coerceAtLeast(1f)

  var currentColumns by remember { mutableIntStateOf(columns) }
  var currentRows by remember { mutableIntStateOf(rows) }

  var scrollOrigin by remember { mutableIntStateOf(0) } // [-historyLinesCount, 0]
  var scrollRemainderPx by remember { mutableFloatStateOf(0f) }

  val isUsingAlternateBuffer = remember(bufferVersion) {
    session.terminalTextBuffer.isUsingAlternateBuffer
  }
  LaunchedEffect(isUsingAlternateBuffer) {
    if (isUsingAlternateBuffer) {
      scrollOrigin = 0
      scrollRemainderPx = 0f
    }
  }
  LaunchedEffect(bufferVersion, isUsingAlternateBuffer) {
    if (!isUsingAlternateBuffer) {
      val historyCount = session.terminalTextBuffer.historyLinesCount
      scrollOrigin = scrollOrigin.coerceIn(-historyCount, 0)
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black)
      .onSizeChanged { size ->
        val newCols = floor(size.width / charWidthPx).toInt().coerceAtLeast(5)
        val newRows = floor(size.height / charHeightPx).toInt().coerceAtLeast(2)
        if (newCols != currentColumns || newRows != currentRows) {
          currentColumns = newCols
          currentRows = newRows
          session.resize(newCols, newRows)
          onResize?.invoke(newCols, newRows)
        }
      }
    ,
  ) {
    AndroidView(
      factory = { context ->
        TerminalInputView(context).apply {
          onCommitText = { text ->
            if (text.isNotEmpty()) {
              Log.d(logTag, "onCommitText len=${text.length}")
              session.sendString(text, userInput = true)
            }
          }
          onKeyEvent = { event ->
            val bytes = mapKeyEventToTerminalBytes(KeyEvent(event), session)
            if (bytes != null) {
              Log.d(logTag, "onKeyEvent keyCode=${event.keyCode} sendBytes=${bytes.size}")
              session.sendBytes(bytes)
              true
            } else {
              false
            }
          }
        }
      },
      modifier = Modifier.matchParentSize(),
      update = { inputView ->
        if (inputViewRef !== inputView) {
          inputViewRef = inputView
        }
        inputView.onCommitText = { text ->
          if (text.isNotEmpty()) {
            Log.d(logTag, "onCommitText len=${text.length}")
            session.sendString(text, userInput = true)
          }
        }
        inputView.onKeyEvent = { event ->
          val bytes = mapKeyEventToTerminalBytes(KeyEvent(event), session)
          if (bytes != null) {
            Log.d(logTag, "onKeyEvent keyCode=${event.keyCode} sendBytes=${bytes.size}")
            session.sendBytes(bytes)
            true
          } else {
            false
          }
        }

        if (!inputView.hasFocus()) {
          inputView.requestFocus()
          val imm = inputView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
          imm.showSoftInput(inputView, InputMethodManager.SHOW_IMPLICIT)
          Log.d(logTag, "requestFocus+showSoftInput")
        }
      },
    )

    Canvas(
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
          detectTapGestures {
            inputViewRef?.let { v ->
              v.requestFocus()
              val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
              imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
              Log.d(logTag, "tap->requestFocus+showSoftInput")
            }
          }
        }
        .pointerInput(isUsingAlternateBuffer, charHeightPx, bufferVersion) {
          if (isUsingAlternateBuffer) return@pointerInput
          detectDragGestures { _, dragAmount ->
            if (charHeightPx <= 0f) return@detectDragGestures
            scrollRemainderPx += dragAmount.y
            val linesDelta = (scrollRemainderPx / charHeightPx).toInt()
            if (linesDelta != 0) {
              val historyCount = session.terminalTextBuffer.historyLinesCount
              scrollOrigin = (scrollOrigin + linesDelta).coerceIn(-historyCount, 0)
              scrollRemainderPx -= linesDelta * charHeightPx
            }
          }
        },
    ) {
      val snapshot = buildTerminalRenderSnapshot(
        terminalTextBuffer = session.terminalTextBuffer,
        styleState = session.terminal.styleState,
        columns = currentColumns,
        rows = currentRows,
        scrollOrigin = scrollOrigin,
      )

      val defaultBg = snapshot.defaultBackground.toComposeColor()

      // Clear.
      drawRect(defaultBg)

      // Cells.
      for (row in 0 until snapshot.rows) {
        val y = row * charHeightPx
        val rowCells = snapshot.cells[row]
        for (col in 0 until snapshot.columns) {
          val cell = rowCells[col]
          val ch = cell.ch
          if (ch == CharUtils.DWC || ch == '\u0000') continue

          val x = col * charWidthPx
          val cellWidth = if (cell.isDoubleWidth) charWidthPx * 2f else charWidthPx

          val bg = cell.background.toComposeColor()
          if (bg != defaultBg) {
            drawRect(color = bg, topLeft = Offset(x, y), size = Size(cellWidth, charHeightPx))
          }

          if (ch == ' ') continue

          val fg = cell.foreground.toComposeColor().let { if (cell.dim) it.copy(alpha = 0.7f) else it }
          val textStyle = baseTextStyle.copy(
            color = fg,
            fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (cell.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (cell.underline) TextDecoration.Underline else null,
          )

          drawText(
            textMeasurer = textMeasurer,
            text = ch.toString(),
            topLeft = Offset(x, y),
            style = textStyle,
          )
        }
      }

      // Cursor (hide while scrolled back).
      if (scrollOrigin == 0 && session.display.cursorVisible) {
        var cursorCol = session.display.cursorX
        val cursorRow = session.display.cursorY - 1
        if (cursorCol in 0 until snapshot.columns && cursorRow in 0 until snapshot.rows) {
          var cell = snapshot.cells[cursorRow][cursorCol]
          if (cell.ch == CharUtils.DWC && cursorCol > 0) {
            cursorCol -= 1
            cell = snapshot.cells[cursorRow][cursorCol]
          }
          val x = cursorCol * charWidthPx
          val y = cursorRow * charHeightPx

          val cursorShape = session.display.cursorShape
          val cursorColor = cell.foreground.toComposeColor().copy(alpha = 0.8f)
          val cursorWidth = if (cell.isDoubleWidth) charWidthPx * 2f else charWidthPx
          val size = Size(cursorWidth, charHeightPx)

          when (cursorShape) {
            com.jediterm.terminal.CursorShape.BLINK_UNDERLINE,
            com.jediterm.terminal.CursorShape.STEADY_UNDERLINE,
            -> {
              drawRect(
                color = cursorColor,
                topLeft = Offset(x, y + charHeightPx - 2f),
                size = Size(cursorWidth, 2f),
              )
            }

            com.jediterm.terminal.CursorShape.BLINK_VERTICAL_BAR,
            com.jediterm.terminal.CursorShape.STEADY_VERTICAL_BAR,
            -> {
              drawRect(
                color = cursorColor,
                topLeft = Offset(x, y),
                size = Size(2f, charHeightPx),
              )
            }

            null,
            com.jediterm.terminal.CursorShape.BLINK_BLOCK,
            com.jediterm.terminal.CursorShape.STEADY_BLOCK,
            -> {
              drawRect(color = cursorColor, topLeft = Offset(x, y), size = size)
              val ch = cell.ch
              if (ch != ' ' && ch != CharUtils.DWC) {
                drawText(
                  textMeasurer = textMeasurer,
                  text = ch.toString(),
                  topLeft = Offset(x, y),
                  style = baseTextStyle.copy(color = cell.background.toComposeColor()),
                )
              }
            }
          }
        }
      }
    }
  }
}

private fun mapKeyEventToTerminalBytes(keyEvent: KeyEvent, session: TerminalSessionManager): ByteArray? {
  val native = keyEvent.nativeKeyEvent
  if (native.action != android.view.KeyEvent.ACTION_DOWN) return null

  val modifiers = buildJediModifiers(native)

  // Enter => CR only.
  if (native.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
    native.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
  ) {
    return byteArrayOf(Ascii.CR)
  }

  // Ctrl + [A-Z] => control char.
  if ((modifiers and InputEvent.CTRL_MASK) != 0) {
    val keyCode = native.keyCode
    if (keyCode in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z) {
      val ctrl = keyCode - android.view.KeyEvent.KEYCODE_A + 1
      return byteArrayOf(ctrl.toByte())
    }
  }

  val vk = when (native.keyCode) {
    android.view.KeyEvent.KEYCODE_DPAD_UP -> JediKeyEvent.VK_UP
    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> JediKeyEvent.VK_DOWN
    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> JediKeyEvent.VK_LEFT
    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> JediKeyEvent.VK_RIGHT

    android.view.KeyEvent.KEYCODE_DEL -> JediKeyEvent.VK_BACK_SPACE
    android.view.KeyEvent.KEYCODE_FORWARD_DEL -> JediKeyEvent.VK_DELETE

    android.view.KeyEvent.KEYCODE_MOVE_HOME -> JediKeyEvent.VK_HOME
    android.view.KeyEvent.KEYCODE_MOVE_END -> JediKeyEvent.VK_END
    android.view.KeyEvent.KEYCODE_PAGE_UP -> JediKeyEvent.VK_PAGE_UP
    android.view.KeyEvent.KEYCODE_PAGE_DOWN -> JediKeyEvent.VK_PAGE_DOWN

    android.view.KeyEvent.KEYCODE_F1 -> JediKeyEvent.VK_F1
    android.view.KeyEvent.KEYCODE_F2 -> JediKeyEvent.VK_F2
    android.view.KeyEvent.KEYCODE_F3 -> JediKeyEvent.VK_F3
    android.view.KeyEvent.KEYCODE_F4 -> JediKeyEvent.VK_F4
    android.view.KeyEvent.KEYCODE_F5 -> JediKeyEvent.VK_F5
    android.view.KeyEvent.KEYCODE_F6 -> JediKeyEvent.VK_F6
    android.view.KeyEvent.KEYCODE_F7 -> JediKeyEvent.VK_F7
    android.view.KeyEvent.KEYCODE_F8 -> JediKeyEvent.VK_F8
    android.view.KeyEvent.KEYCODE_F9 -> JediKeyEvent.VK_F9
    android.view.KeyEvent.KEYCODE_F10 -> JediKeyEvent.VK_F10
    android.view.KeyEvent.KEYCODE_F11 -> JediKeyEvent.VK_F11
    android.view.KeyEvent.KEYCODE_F12 -> JediKeyEvent.VK_F12

    android.view.KeyEvent.KEYCODE_INSERT -> JediKeyEvent.VK_INSERT
    android.view.KeyEvent.KEYCODE_ESCAPE,
    android.view.KeyEvent.KEYCODE_BACK,
    -> JediKeyEvent.VK_ESCAPE

    android.view.KeyEvent.KEYCODE_TAB -> JediKeyEvent.VK_TAB

    else -> null
  }

  // Escape and Tab aren't encoded by TerminalKeyEncoder; handle explicitly.
  if (vk == JediKeyEvent.VK_ESCAPE) return byteArrayOf(Ascii.ESC)
  if (vk == JediKeyEvent.VK_TAB) return byteArrayOf(Ascii.HT)

  return vk?.let { session.terminal.getCodeForKey(it, modifiers) }
}

private fun buildJediModifiers(nativeKeyEvent: android.view.KeyEvent): Int {
  var modifiers = 0
  val metaState = nativeKeyEvent.metaState
  if ((metaState and android.view.KeyEvent.META_SHIFT_ON) != 0) modifiers = modifiers or InputEvent.SHIFT_MASK
  if ((metaState and android.view.KeyEvent.META_ALT_ON) != 0) modifiers = modifiers or InputEvent.ALT_MASK
  if ((metaState and android.view.KeyEvent.META_CTRL_ON) != 0) modifiers = modifiers or InputEvent.CTRL_MASK
  if ((metaState and android.view.KeyEvent.META_META_ON) != 0) modifiers = modifiers or InputEvent.META_MASK
  return modifiers
}

private fun com.jediterm.core.Color.toComposeColor(): Color {
  return Color(red = getRed(), green = getGreen(), blue = getBlue(), alpha = getAlpha())
}
