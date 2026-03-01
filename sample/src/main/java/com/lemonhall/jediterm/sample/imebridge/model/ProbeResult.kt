package com.lemonhall.jediterm.sample.imebridge.model

sealed class ProbeResult {
    data class Success(val metaJson: String) : ProbeResult()
    data object NotFound : ProbeResult()
    data object Timeout : ProbeResult()
    data class Error(val message: String) : ProbeResult()
}
