package com.lemonhall.jediterm.android

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

fun mockEchoConnector(): TtyConnector = EchoTtyConnector()

fun mockColorTestConnector(): TtyConnector = EchoTtyConnector(initialOutput = buildAnsiColorTestOutput())

private class EchoTtyConnector(
  initialOutput: String = "",
) : TtyConnector {
  private val connected = AtomicBoolean(true)
  private val queue = LinkedBlockingQueue<Int>()

  init {
    if (initialOutput.isNotEmpty()) {
      enqueue(initialOutput)
    }
  }

  override fun read(buf: CharArray, offset: Int, length: Int): Int {
    try {
      val first = queue.take()
      if (first == EOF) return -1

      buf[offset] = first.toChar()
      var written = 1
      while (written < length) {
        val next = queue.poll() ?: break
        if (next == EOF) return if (written == 0) -1 else written
        buf[offset + written] = next.toChar()
        written++
      }
      return written
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw IOException("Interrupted", e)
    }
  }

  override fun write(bytes: ByteArray) {
    val text = String(bytes, StandardCharsets.UTF_8)
    enqueue(text)
  }

  override fun write(string: String) {
    enqueue(string)
  }

  private fun enqueue(text: String) {
    if (!connected.get()) return
    for (ch in text) {
      queue.offer(ch.code)
    }
  }

  override fun isConnected(): Boolean = connected.get()

  override fun resize(termSize: TermSize) = Unit

  override fun ready(): Boolean = queue.isNotEmpty()

  override fun getName(): String = "mock-echo"

  override fun close() {
    if (connected.compareAndSet(true, false)) {
      queue.offer(EOF)
    }
  }

  companion object {
    private const val EOF = -1
  }
}

private fun buildAnsiColorTestOutput(): String {
  return buildString {
    append("\u001b[2J\u001b[H")
    append("jediterm-android ANSI render test\r\n\r\n")

    append("\u001b[1mBold\u001b[0m  ")
    append("\u001b[3mItalic\u001b[0m  ")
    append("\u001b[4mUnderline\u001b[0m  ")
    append("\u001b[7mInverse\u001b[0m\r\n\r\n")

    append("16 colors (bg):\r\n")
    for (i in 0 until 16) {
      append("\u001b[48;5;")
      append(i)
      append("m  \u001b[0m")
    }
    append("\r\n\r\n")

    append("256 colors (bg, 16-231):\r\n")
    var col = 0
    for (i in 16..231) {
      append("\u001b[48;5;")
      append(i)
      append("m ")
      append("\u001b[0m")
      col++
      if (col >= 64) {
        append("\r\n")
        col = 0
      }
    }
    append("\r\n\r\n")

    append("True color:\r\n")
    append("\u001b[48;2;255;0;0m  \u001b[0m ")
    append("\u001b[48;2;0;255;0m  \u001b[0m ")
    append("\u001b[48;2;0;0;255m  \u001b[0m ")
    append("\u001b[38;2;255;165;0mOrange\u001b[0m\r\n\r\n")

    append("CJK double-width:\r\n")
    append("你好世界 Hello\r\n")
    append("中文混合English测试\r\n")
    append("├──目录1\r\n")
    append("│  ├──文件.txt\r\n")
    append("│  └──子目录\r\n")
    append("└──目录2\r\n\r\n")

    append("Scrollback (swipe/drag):\r\n")
    for (i in 1..80) {
      append("Line ")
      append(i)
      append("\r\n")
    }
    append("\r\nType here. Arrow keys / Esc / Tab / Ctrl+<key> should work.\r\n")
  }
}
