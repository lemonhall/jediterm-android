package com.lemonhall.jediterm.android

import com.jediterm.core.Color
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.emulator.ColorPaletteImpl
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.util.CharUtils

data class TerminalRenderCell(
  val ch: Char,
  val foreground: Color,
  val background: Color,
  val bold: Boolean,
  val italic: Boolean,
  val underline: Boolean,
  val dim: Boolean,
  val hidden: Boolean,
  val isDoubleWidth: Boolean = false,
)

data class TerminalRenderSnapshot(
  val columns: Int,
  val rows: Int,
  val defaultForeground: Color,
  val defaultBackground: Color,
  val cells: Array<Array<TerminalRenderCell>>,
)

internal fun buildTerminalRenderSnapshot(
  terminalTextBuffer: TerminalTextBuffer,
  styleState: StyleState,
  columns: Int,
  rows: Int,
  scrollOrigin: Int,
  colorPalette: ColorPalette = ColorPaletteImpl.XTERM_PALETTE,
): TerminalRenderSnapshot {
  val defaultFg = resolveForegroundColor(safeDefaultForeground(styleState), colorPalette)
  val defaultBg = resolveBackgroundColor(safeDefaultBackground(styleState), colorPalette)

  val blank = TerminalRenderCell(
    ch = ' ',
    foreground = defaultFg,
    background = defaultBg,
    bold = false,
    italic = false,
    underline = false,
    dim = false,
    hidden = false,
  )
  val cells = Array(rows) { Array(columns) { blank } }

  terminalTextBuffer.lock()
  try {
    val clampedScrollOrigin = scrollOrigin.coerceIn(-terminalTextBuffer.historyLinesCount, 0)
    terminalTextBuffer.processHistoryAndScreenLines(
      scrollOrigin = clampedScrollOrigin,
      maximalLinesToProcess = rows,
      consumer = object : com.jediterm.terminal.StyledTextConsumer {
        override fun consume(
          x: Int,
          y: Int,
          style: TextStyle,
          characters: com.jediterm.terminal.model.CharBuffer,
          startRow: Int,
        ) {
          writeCellsFromEntry(
            cells = cells,
            columns = columns,
            rows = rows,
            defaultForeground = defaultFg,
            defaultBackground = defaultBg,
            styleState = styleState,
            style = style,
            x = x,
            screenRow = y - startRow,
            characters = characters,
            colorPalette = colorPalette,
          )
        }

        override fun consumeNul(
          x: Int,
          y: Int,
          nulIndex: Int,
          style: TextStyle,
          characters: com.jediterm.terminal.model.CharBuffer,
          startRow: Int,
        ) {
          writeCellsFromEntry(
            cells = cells,
            columns = columns,
            rows = rows,
            defaultForeground = defaultFg,
            defaultBackground = defaultBg,
            styleState = styleState,
            style = style,
            x = x,
            screenRow = y - startRow,
            characters = characters,
            colorPalette = colorPalette,
          )
        }

        override fun consumeQueue(x: Int, y: Int, nulIndex: Int, startRow: Int) = Unit
      },
    )
  } finally {
    terminalTextBuffer.unlock()
  }

  return TerminalRenderSnapshot(
    columns = columns,
    rows = rows,
    defaultForeground = defaultFg,
    defaultBackground = defaultBg,
    cells = cells,
  )
}

private fun resolveForegroundColor(color: com.jediterm.terminal.TerminalColor, palette: ColorPalette): Color {
  return if (color.isIndexed) palette.getForeground(color) else color.toColor()
}

private fun resolveBackgroundColor(color: com.jediterm.terminal.TerminalColor, palette: ColorPalette): Color {
  return if (color.isIndexed) palette.getBackground(color) else color.toColor()
}

internal fun TextStyle.isBold(): Boolean = hasOption(TextStyle.Option.BOLD)
internal fun TextStyle.isItalic(): Boolean = hasOption(TextStyle.Option.ITALIC)
internal fun TextStyle.isUnderline(): Boolean = hasOption(TextStyle.Option.UNDERLINED)
internal fun TextStyle.isDim(): Boolean = hasOption(TextStyle.Option.DIM)
internal fun TextStyle.isHidden(): Boolean = hasOption(TextStyle.Option.HIDDEN)
internal fun TextStyle.isInverse(): Boolean = hasOption(TextStyle.Option.INVERSE)

internal fun sanitizeTerminalChar(ch: Char): Char {
  if (ch == CharUtils.NUL_CHAR) return ' '
  return ch
}

private fun writeCellsFromEntry(
  cells: Array<Array<TerminalRenderCell>>,
  columns: Int,
  rows: Int,
  defaultForeground: Color,
  defaultBackground: Color,
  styleState: StyleState,
  style: TextStyle,
  x: Int,
  screenRow: Int,
  characters: com.jediterm.terminal.model.CharBuffer,
  colorPalette: ColorPalette,
) {
  if (screenRow !in 0 until rows) return

  val baseForeground = style.foreground ?: safeDefaultForeground(styleState)
  val baseBackground = style.background ?: safeDefaultBackground(styleState)

  var foreground = resolveForegroundColor(baseForeground, colorPalette)
  var background = resolveBackgroundColor(baseBackground, colorPalette)

  if (style.isInverse()) {
    val tmp = foreground
    foreground = background
    background = tmp
  }

  val bold = style.isBold()
  val italic = style.isItalic()
  val underline = style.isUnderline()
  val dim = style.isDim()
  val hidden = style.isHidden()

  val visibleForeground = if (hidden) background else foreground

  for (i in 0 until characters.length) {
    val col = x + i
    if (col !in 0 until columns) continue
    val raw = characters[i]
    val ch = if (hidden) ' ' else sanitizeTerminalChar(raw)
    val hasDwcPlaceholder = (i + 1 < characters.length) && (characters[i + 1] == CharUtils.DWC)
    val isDoubleWidth = raw != CharUtils.DWC && CharUtils.isDoubleWidthCharacter(raw.code, false) && (hasDwcPlaceholder || col == columns - 1)
    cells[screenRow][col] = TerminalRenderCell(
      ch = ch,
      foreground = visibleForeground,
      background = background,
      bold = bold,
      italic = italic,
      underline = underline,
      dim = dim,
      hidden = hidden,
      isDoubleWidth = isDoubleWidth,
    )
  }
}

private fun safeDefaultForeground(styleState: StyleState): TerminalColor {
  return try {
    styleState.defaultForeground
  } catch (_: Throwable) {
    TerminalColor.index(15)
  }
}

private fun safeDefaultBackground(styleState: StyleState): TerminalColor {
  return try {
    styleState.defaultBackground
  } catch (_: Throwable) {
    TerminalColor.index(0)
  }
}
