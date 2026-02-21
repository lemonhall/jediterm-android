package com.lemonhall.jediterm.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.model.TerminalModelListener

@Composable
fun ComposeTerminalView(
  ttyConnector: TtyConnector,
  modifier: Modifier = Modifier,
  columns: Int = 80,
  rows: Int = 24,
) {
  val session = remember { TerminalSessionManager(columns = columns, rows = rows) }
  var bufferVersion by remember { mutableIntStateOf(0) }

  DisposableEffect(session) {
    val listener = TerminalModelListener { bufferVersion++ }
    session.addModelListener(listener)
    onDispose { session.removeModelListener(listener) }
  }

  LaunchedEffect(session, ttyConnector) {
    session.startSession(ttyConnector)
  }

  DisposableEffect(session) {
    onDispose { session.stopSession() }
  }

  val terminalText = remember(bufferVersion) {
    val buffer = session.terminalTextBuffer
    buffer.lock()
    try {
      buildString {
        for (row in 0 until buffer.height) {
          append(buffer.getLine(row).text)
          append('\n')
        }
      }
    }
    finally {
      buffer.unlock()
    }
  }

  var inputValue by remember { mutableStateOf("") }
  val focusRequester = remember { FocusRequester() }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black),
  ) {
    BasicText(
      text = terminalText,
      modifier = Modifier.fillMaxSize(),
      style = TextStyle(
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
      ),
    )

    BasicTextField(
      value = inputValue,
      onValueChange = { newValue ->
        if (newValue.isNotEmpty()) {
          session.sendString(newValue)
          inputValue = ""
        } else {
          inputValue = ""
        }
      },
      modifier = Modifier
        .fillMaxSize()
        .focusRequester(focusRequester),
      textStyle = TextStyle(color = Color.Transparent),
    )
  }

  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }
}

