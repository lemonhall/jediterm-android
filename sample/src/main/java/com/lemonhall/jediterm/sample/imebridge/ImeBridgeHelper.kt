package com.lemonhall.jediterm.sample.imebridge

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.lemonhall.jediterm.sample.JSchTtyConnector
import com.lemonhall.jediterm.sample.imebridge.model.SessionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ImeBridgeHelper(
    private val context: Context,
    private val connector: JSchTtyConnector,
    config: ImeBridgeConfig = ImeBridgeConfig(enabled = false),
) {
    companion object {
        private const val TAG = "ImeBridgeHelper"
    }

    private val controller = ImeBridgeController(
        context = context,
        enabled = config.enabled,
        debounceMs = config.debounceMs,
        probeTimeoutMs = config.probeTimeoutMs,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionId = UUID.randomUUID().toString()
    private var lastCwd: String? = null

    fun start() {
        if (!controller.enabled) return

        // 设置目录变化回调
        connector.onCwdChangeCallback = { cwd ->
            lastCwd = cwd
            Log.d(TAG, "Directory changed detected: $cwd")

            // 显示Toast提示（调试用）
            // scope.launch(Dispatchers.Main) {
            //     Toast.makeText(
            //         context,
            //         "IME Bridge: 检测到目录变化\n$cwd",
            //         Toast.LENGTH_SHORT
            //     ).show()
            // }

            triggerCheck()
        }

        scope.launch {
            delay(5000)
            triggerInitialCheck()
        }
    }

    fun stop() {
        controller.destroy()
        scope.cancel()
    }

    fun triggerCheck() {
        scope.launch {
            performCheck()
        }
    }

    private suspend fun triggerInitialCheck() {
        performCheck()
    }

    private suspend fun performCheck(cwd: String? = null) {
        val (host, user, port) = connector.getSessionInfo()
        val session = connector.getSession() ?: return

        val actualCwd = cwd ?: lastCwd ?: "/home/$user"

        val context = SessionContext(
            host = host,
            user = user,
            cwd = actualCwd,
            sessionId = sessionId,
        )

        controller.onContextChanged(
            context = context,
            session = session,
        )
    }
}
