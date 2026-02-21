package com.lemonhall.jediterm.sample

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jediterm.terminal.TtyConnector
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class JSchTtyConnector(
  private val host: String,
  private val port: Int,
  private val username: String,
  private val password: String?,
  private val privateKeyPath: String?,
  private val passphrase: String?,
) : TtyConnector {

  private var session: Session? = null
  private var channel: ChannelShell? = null
  private var inputStream: InputStream? = null
  private var outputStream: OutputStream? = null
  private var reader: InputStreamReader? = null

  fun connect(columns: Int = 80, rows: Int = 24) {
    val jsch = JSch()

    if (!privateKeyPath.isNullOrBlank()) {
      if (!passphrase.isNullOrBlank()) {
        jsch.addIdentity(privateKeyPath, passphrase)
      } else {
        jsch.addIdentity(privateKeyPath)
      }
    }

    val sshSession = jsch.getSession(username, host, port).apply {
      if (!password.isNullOrBlank()) {
        setPassword(password)
      }
      setConfig("StrictHostKeyChecking", "no")
      connect(10_000)
    }

    val sshChannel = (sshSession.openChannel("shell") as ChannelShell).apply {
      setPtyType("xterm-256color", columns, rows, 0, 0)
      connect(10_000)
    }

    session = sshSession
    channel = sshChannel
    inputStream = sshChannel.inputStream
    outputStream = sshChannel.outputStream
    reader = InputStreamReader(sshChannel.inputStream, StandardCharsets.UTF_8)
  }

  override fun read(buf: CharArray, offset: Int, length: Int): Int {
    val localReader = reader ?: throw IOException("SSH channel not connected")
    return localReader.read(buf, offset, length)
  }

  override fun write(bytes: ByteArray) {
    val out = outputStream ?: throw IOException("SSH channel not connected")
    out.write(bytes)
    out.flush()
  }

  override fun write(string: String) {
    write(string.toByteArray(StandardCharsets.UTF_8))
  }

  override fun isConnected(): Boolean {
    val ch = channel
    return ch != null && ch.isConnected && !ch.isClosed
  }

  override fun ready(): Boolean {
    val input = inputStream ?: return false
    return input.available() > 0
  }

  override fun getName(): String = "ssh [$username@$host:$port]"

  override fun close() {
    try {
      channel?.disconnect()
    } catch (_: Exception) {
    }
    try {
      session?.disconnect()
    } catch (_: Exception) {
    }
  }

  fun resizePty(columns: Int, rows: Int) {
    val ch = channel ?: return
    if (ch.isConnected && !ch.isClosed) {
      ch.setPtySize(columns, rows, 0, 0)
    }
  }
}
