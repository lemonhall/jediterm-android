package com.lemonhall.jediterm.sample.imebridge

data class ImeBridgeConfig(
    val enabled: Boolean = false,
    val targetPackage: String = "com.lsl.lemonhall.fcitx5",
    val probeTimeoutMs: Long = 1500,
    val debounceMs: Long = 500,
)
