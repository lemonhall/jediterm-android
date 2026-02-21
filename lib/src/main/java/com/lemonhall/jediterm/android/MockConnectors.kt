package com.lemonhall.jediterm.android

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

fun mockEchoConnector(): TtyConnector = EchoTtyConnector()

private class EchoTtyConnector : TtyConnector {
  private val connected = AtomicBoolean(true)
  private val queue = LinkedBlockingQueue<Int>()

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

