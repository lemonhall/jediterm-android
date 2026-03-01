package com.lemonhall.jediterm.sample.imebridge.model

data class SessionContext(
    val host: String,
    val user: String,
    val cwd: String,
    val sessionId: String,
) {
    fun toKey(): String = "$host:$user:$cwd:$sessionId"
}
