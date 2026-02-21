package com.lemonhall.jediterm.android

import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPaletteImpl
import com.jediterm.terminal.model.CharBuffer
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.util.CharUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalRenderSnapshotTest {
  @Test
  fun snapshot_doesNotCrash_whenDefaultStyleNotInitialized() {
    val styleState = StyleState()
    val buffer = TerminalTextBuffer(width = 5, height = 1, styleState = styleState)
    buffer.writeString(0, 1, CharBuffer("A"))

    val snapshot = buildTerminalRenderSnapshot(
      terminalTextBuffer = buffer,
      styleState = styleState,
      columns = 5,
      rows = 1,
      scrollOrigin = 0,
    )

    val expectedDefaultFg = ColorPaletteImpl.XTERM_PALETTE.getForeground(TerminalColor.index(15))
    val expectedDefaultBg = ColorPaletteImpl.XTERM_PALETTE.getBackground(TerminalColor.index(0))
    assertEquals(expectedDefaultFg, snapshot.defaultForeground)
    assertEquals(expectedDefaultBg, snapshot.defaultBackground)
    assertEquals('A', snapshot.cells[0][0].ch)
  }

  @Test
  fun snapshot_fallsBackToDefaultColors_whenStyleColorsNull() {
    val styleState = StyleState().apply {
      setDefaultStyle(TextStyle(TerminalColor.index(15), TerminalColor.index(0)))
      setCurrent(TextStyle.EMPTY)
    }
    val buffer = TerminalTextBuffer(width = 5, height = 2, styleState = styleState)
    buffer.writeString(0, 1, CharBuffer("A"))

    val snapshot = buildTerminalRenderSnapshot(
      terminalTextBuffer = buffer,
      styleState = styleState,
      columns = 5,
      rows = 2,
      scrollOrigin = 0,
    )

    assertEquals('A', snapshot.cells[0][0].ch)
  }

  @Test
  fun snapshot_keepsDwcPlaceholderInSecondCell() {
    val styleState = StyleState().apply {
      setDefaultStyle(TextStyle(TerminalColor.index(15), TerminalColor.index(0)))
      setCurrent(TextStyle(TerminalColor.index(15), TerminalColor.index(0)))
    }
    val buffer = TerminalTextBuffer(width = 4, height = 1, styleState = styleState)
    buffer.writeString(0, 1, CharBuffer(charArrayOf('你', CharUtils.DWC), 0, 2))

    val snapshot = buildTerminalRenderSnapshot(
      terminalTextBuffer = buffer,
      styleState = styleState,
      columns = 4,
      rows = 1,
      scrollOrigin = 0,
    )

    assertEquals('你', snapshot.cells[0][0].ch)
    assertEquals(CharUtils.DWC, snapshot.cells[0][1].ch)
  }

  @Test
  fun snapshot_appliesInverseBySwappingFgBg() {
    val inverse = TextStyle.Builder()
      .setForeground(TerminalColor.index(1))
      .setBackground(TerminalColor.index(0))
      .setOption(TextStyle.Option.INVERSE, true)
      .build()

    val styleState = StyleState().apply {
      setDefaultStyle(TextStyle(TerminalColor.index(15), TerminalColor.index(0)))
      setCurrent(inverse)
    }

    val buffer = TerminalTextBuffer(width = 2, height = 1, styleState = styleState)
    buffer.writeString(0, 1, CharBuffer("X"))

    val snapshot = buildTerminalRenderSnapshot(
      terminalTextBuffer = buffer,
      styleState = styleState,
      columns = 2,
      rows = 1,
      scrollOrigin = 0,
    )

    val cell = snapshot.cells[0][0]
    val expectedBlack = ColorPaletteImpl.XTERM_PALETTE.getForeground(TerminalColor.index(0))
    val expectedRed = ColorPaletteImpl.XTERM_PALETTE.getForeground(TerminalColor.index(1))
    assertEquals(expectedBlack, cell.foreground)
    assertEquals(expectedRed, cell.background)
  }
}
