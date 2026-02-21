package com.lemonhall.jediterm.sample

import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jediterm.terminal.TtyConnector
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.io.BufferedInputStream

class JSchTtyConnector(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String?,
    private val privateKeyPath: String?,
    private val passphrase: String?,
) : TtyConnector {
    private val logTag = "JeditermSsh"

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var bufferedInput: BufferedInputStream? = null
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
            setPtyType("xterm-256color", columns, rows, columns * 8, rows * 16)
        }

        val input = sshChannel.inputStream
        val output = sshChannel.outputStream

        sshChannel.connect(10_000)

        Log.d(logTag, "connect OK: channel.isConnected=${sshChannel.isConnected} session.isConnected=${sshSession.isConnected}")

        session = sshSession
        channel = sshChannel
        outputStream = output

        val buffered = BufferedInputStream(input)
        bufferedInput = buffered
        reader = InputStreamReader(buffered, StandardCharsets.UTF_8)

        // 修复 UTF-8 环境
        val initCmd = "export LANG=en_US.UTF-8; export LC_ALL=en_US.UTF-8; stty iutf8\n"
        outputStream!!.write(initCmd.toByteArray(StandardCharsets.UTF_8))
        outputStream!!.flush()
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val localReader = reader ?: throw IOException("SSH channel not connected")
        val n = localReader.read(buf, offset, length)
        if (n > 0) {
            logNonAscii(buf, offset, n)
        }
        return n
    }

    private fun logNonAscii(buf: CharArray, offset: Int, count: Int) {
        try {
            var hasNonAscii = false
            val end = minOf(offset + count, offset + 20, buf.size)
            for (i in offset until end) {
                if (buf[i].code > 0x7F) { hasNonAscii = true; break }
            }
            if (hasNonAscii) {
                val sb = StringBuilder()
                for (i in offset until end) {
                    sb.append(Integer.toHexString(buf[i].code)).append(' ')
                }
                Log.e("JeditermDbg", "read chars: $sb")
            }
        } catch (_: Exception) {}
    }

    override fun write(bytes: ByteArray) {
        val out = outputStream ?: throw IOException("SSH channel not connected")
        Log.e("JeditermDbg", "write raw bytes=${bytes.map { "0x%02X".format(it) }}")
        out.write(bytes)
        out.flush()
    }

    override fun write(string: String) {
        val bytes = string.toByteArray(StandardCharsets.UTF_8)
        write(bytes)
        Log.e("JeditermDbg", "write string='$string' bytes=${bytes.map { "0x%02X".format(it) }}")
    }

    override fun isConnected(): Boolean {
        val ch = channel
        return ch != null && ch.isConnected && !ch.isClosed
    }

    override fun ready(): Boolean {
        val input = bufferedInput ?: return false
        return input.available() > 0
    }

    override fun getName(): String = "ssh [$username@$host:$port]"

    override fun close() {
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
    }

    fun resizePty(columns: Int, rows: Int) {
        val ch = channel ?: return
        if (ch.isConnected && !ch.isClosed) {
            ch.setPtySize(columns, rows, columns * 8, rows * 16)
        }
    }
}