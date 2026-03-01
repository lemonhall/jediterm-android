package com.lemonhall.jediterm.sample.imebridge

import android.util.Log
import com.lemonhall.jediterm.sample.imebridge.model.DispatchEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DispatchGuard(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 500,
) {
    companion object {
        private const val TAG = "DispatchGuard"
    }

    private var lastEvent: DispatchEvent? = null
    private var pendingJob: Job? = null

    fun shouldDispatch(event: DispatchEvent, onDispatch: (DispatchEvent) -> Unit) {
        val last = lastEvent
        if (last != null &&
            last.targetPackage == event.targetPackage &&
            last.payloadHash() == event.payloadHash()) {
            Log.d(TAG, "Duplicate event, skipping")
            return
        }

        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(debounceMs)
            Log.d(TAG, "Dispatching after debounce: hash=${event.payloadHash()}")
            onDispatch(event)
            lastEvent = event
        }
    }

    fun reset() {
        pendingJob?.cancel()
        lastEvent = null
    }
}
