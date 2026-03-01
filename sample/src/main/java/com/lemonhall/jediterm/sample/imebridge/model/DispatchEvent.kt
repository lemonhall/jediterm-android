package com.lemonhall.jediterm.sample.imebridge.model

data class DispatchEvent(
    val targetPackage: String,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun payloadHash(): String = payload.hashCode().toString(16)
}
