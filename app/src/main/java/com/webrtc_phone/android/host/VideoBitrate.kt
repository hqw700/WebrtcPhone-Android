package com.webrtc_phone.android.host

import org.json.JSONObject

internal data class VideoBitrate(val minBps: Int?, val startBps: Int, val maxBps: Int) {
    companion object {
        private const val DEFAULT_BPS = 4_000_000

        fun from(options: JSONObject?): VideoBitrate {
            val requested = options.positiveBps("bitrate")
            if (options?.optBoolean("bwe", true) == false) {
                val cap = requested ?: DEFAULT_BPS
                return VideoBitrate(null, cap, cap)
            }

            val max = options.positiveBps("max_bitrate") ?: requested ?: DEFAULT_BPS
            val min = options.positiveBps("min_bitrate")?.coerceAtMost(max)
            val start = (requested ?: DEFAULT_BPS).coerceIn(min ?: 1, max)
            return VideoBitrate(min, start, max)
        }

        private fun JSONObject?.positiveBps(key: String): Int? =
            this?.optDouble(key)?.takeIf { it in 1.0..Int.MAX_VALUE.toDouble() }?.toInt()
    }
}
