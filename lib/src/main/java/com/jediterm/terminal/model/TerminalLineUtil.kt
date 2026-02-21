package com.jediterm.terminal.model

// Accessor to package-private stuff in TerminalLine (used from Java code too).
object TerminalLineUtil {
  @JvmStatic
  fun incModificationCount(line: TerminalLine) {
    line.incrementAndGetModificationCount()
  }

  @JvmStatic
  fun getModificationCount(line: TerminalLine): Int = line.modificationCount
}
