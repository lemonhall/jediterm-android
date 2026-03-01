package com.lemonhall.jediterm.sample.imebridge

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import com.lemonhall.jediterm.sample.imebridge.model.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream

class RemoteMetaProbe(
    private val probeTimeoutMs: Long = 1500,
    private val maxSizeBytes: Int = 64 * 1024,
) {
    companion object {
        private const val TAG = "RemoteMetaProbe"
        private const val META_PATH = ".ime/meta.json"
    }

    suspend fun probe(session: Session, workingDir: String): ProbeResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(probeTimeoutMs) {
                Log.d(TAG, "Starting probe for $META_PATH in directory: $workingDir")

                // 先检查文件是否存在（在指定目录下执行）
                val testCmd = "cd '$workingDir' && test -f $META_PATH && echo EXISTS || echo NOT_FOUND"
                val testResult = execCommand(session, testCmd)

                if (!testResult.contains("EXISTS")) {
                    Log.d(TAG, "✗ Meta file not found in $workingDir")
                    return@withTimeout ProbeResult.NotFound
                }

                Log.d(TAG, "✓ Meta file exists, reading content...")

                // 读取文件内容（在指定目录下执行）
                val readCmd = "cd '$workingDir' && cat $META_PATH"
                val content = execCommand(session, readCmd)

                if (content.isEmpty()) {
                    Log.d(TAG, "✗ Empty meta file")
                    return@withTimeout ProbeResult.NotFound
                }

                if (content.length > maxSizeBytes) {
                    Log.w(TAG, "✗ Meta file too large: ${content.length} bytes")
                    return@withTimeout ProbeResult.Error("File too large")
                }

                Log.i(TAG, "✓ Meta file read successfully, size=${content.length}")
                Log.d(TAG, "Content: ${content.trim()}")
                ProbeResult.Success(content.trim())
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Probe timeout")
            ProbeResult.Timeout
        } catch (e: Exception) {
            Log.e(TAG, "Probe error", e)
            ProbeResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun execCommand(session: Session, command: String): String {
        var channel: ChannelExec? = null
        try {
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)

            val outputStream = ByteArrayOutputStream()
            channel.outputStream = outputStream

            channel.connect(3000)

            // 等待命令执行完成
            while (!channel.isClosed) {
                Thread.sleep(50)
            }

            return outputStream.toString("UTF-8")
        } finally {
            channel?.disconnect()
        }
    }
}
