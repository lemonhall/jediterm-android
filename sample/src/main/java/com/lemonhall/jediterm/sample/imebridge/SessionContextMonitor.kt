package com.lemonhall.jediterm.sample.imebridge

import android.util.Log
import com.lemonhall.jediterm.sample.imebridge.model.SessionContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionContextMonitor {
    companion object {
        private const val TAG = "SessionContextMonitor"
    }

    private val _currentContext = MutableStateFlow<SessionContext?>(null)
    val currentContext: StateFlow<SessionContext?> = _currentContext.asStateFlow()

    fun updateContext(context: SessionContext) {
        val previous = _currentContext.value
        if (previous?.toKey() != context.toKey()) {
            Log.d(TAG, "Context changed: ${context.toKey()}")
            _currentContext.value = context
        }
    }

    fun clearContext() {
        _currentContext.value = null
    }
}
