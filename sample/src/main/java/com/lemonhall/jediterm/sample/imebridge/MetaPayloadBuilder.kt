package com.lemonhall.jediterm.sample.imebridge

import com.lemonhall.jediterm.sample.imebridge.model.ProbeResult
import org.json.JSONObject

class MetaPayloadBuilder {
    companion object {
        private const val BASE_FALLBACK = """{"version":1,"dict_profiles":["base"]}"""

        fun build(probeResult: ProbeResult): String {
            return when (probeResult) {
                is ProbeResult.Success -> {
                    try {
                        JSONObject(probeResult.metaJson)
                        probeResult.metaJson
                    } catch (e: Exception) {
                        BASE_FALLBACK
                    }
                }
                is ProbeResult.NotFound,
                is ProbeResult.Timeout,
                is ProbeResult.Error -> BASE_FALLBACK
            }
        }
    }
}
