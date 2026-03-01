package com.lemonhall.jediterm.sample.imebridge

import android.content.Context
import android.util.Log
import com.jcraft.jsch.Session
import com.lemonhall.jediterm.sample.imebridge.model.DispatchEvent
import com.lemonhall.jediterm.sample.imebridge.model.SessionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class ImeBridgeController(
    context: Context,
    val enabled: Boolean = true,
    debounceMs: Long = 500,
    probeTimeoutMs: Long = 1500,
) {
    companion object {
        private const val TAG = "ImeBridgeController"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val monitor = SessionContextMonitor()
    private val probe = RemoteMetaProbe(probeTimeoutMs)
    private val payloadBuilder = MetaPayloadBuilder
    private val dispatcher = ImeBroadcastDispatcher(context)
    private val guard = DispatchGuard(scope, debounceMs)

    init {
        if (enabled) {
            observeContextChanges()
        }
    }

    private fun observeContextChanges() {
        scope.launch {
            monitor.currentContext
                .filterNotNull()
                .distinctUntilChanged { old, new -> old.toKey() == new.toKey() }
                .collect { context ->
                    Log.d(TAG, "Triggering bridge for context: ${context.toKey()}")
                }
        }
    }

    fun onContextChanged(
        context: SessionContext,
        session: Session,
    ) {
        if (!enabled) return

        scope.launch {
            try {
                val probeResult = probe.probe(session, context.cwd)
                val payload = payloadBuilder.build(probeResult)
                val event = DispatchEvent(
                    targetPackage = "com.lsl.lemonhall.fcitx5",
                    payload = payload,
                )

                guard.shouldDispatch(event) { e ->
                    dispatcher.dispatch(e.payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bridge flow error", e)
            }
        }
    }

    fun destroy() {
        guard.reset()
        scope.cancel()
    }
}
