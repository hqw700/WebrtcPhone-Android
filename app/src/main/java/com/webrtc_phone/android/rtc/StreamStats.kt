package com.webrtc_phone.android.rtc

import org.webrtc.RTCStatsReport

/** 投屏实时指标（1s 刷新） */
data class StreamStats(
    val rttMs: Int = 0,
    val fps: Int = 0,
    val bitrateBps: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val packetLossPct: Float = 0f,
    val jitterMs: Int = 0,
    val decodeMs: Int = 0,
    val jitterBufferMs: Int = 0,
    val framesDropped: Long = 0,
    val codec: String = "",
    val decoder: String = "",
    /** host / srflx / prflx / relay */
    val candidateType: String = "",
    val protocol: String = "",
) {
    /** 估算端到端延迟：RTT/2 + 抖动缓冲 + 解码 + 渲染常量 */
    val latencyMs: Int
        get() = if (rttMs == 0 && jitterBufferMs == 0) 0 else rttMs / 2 + jitterBufferMs + decodeMs + 8

    val networkLabel: String
        get() = when (candidateType) {
            "host" -> "局域网直连"
            "srflx", "prflx" -> "P2P 穿透"
            "relay" -> "TURN 中继"
            else -> ""
        }

    val quality: SignalQuality
        get() = when {
            latencyMs == 0 -> SignalQuality.UNKNOWN
            latencyMs < 60 && packetLossPct < 1f -> SignalQuality.EXCELLENT
            latencyMs < 120 && packetLossPct < 3f -> SignalQuality.GOOD
            latencyMs < 250 -> SignalQuality.FAIR
            else -> SignalQuality.POOR
        }
}

enum class SignalQuality { UNKNOWN, EXCELLENT, GOOD, FAIR, POOR }

/** 把累积型 getStats 报告转换成瞬时值（需要上一次采样做差分） */
class StatsCalculator {
    private var lastTs = 0.0
    private var lastBytes = 0L
    private var lastFramesDecoded = 0L
    private var lastDecodeTime = 0.0
    private var lastJbDelay = 0.0
    private var lastJbEmitted = 0L
    private var lastPacketsLost = 0L
    private var lastPacketsRecv = 0L

    fun compute(report: RTCStatsReport): StreamStats {
        val map = report.statsMap
        var rtt = 0.0
        var candidateType = ""
        var protocol = ""
        var codec = ""
        var out = StreamStats()

        val pair = map.values.firstOrNull {
            it.type == "candidate-pair" && it.members["state"] == "succeeded" &&
                (it.members["nominated"] == true || it.members["selected"] == true)
        } ?: map.values.firstOrNull { it.type == "candidate-pair" && it.members["state"] == "succeeded" }
        if (pair != null) {
            rtt = num(pair.members["currentRoundTripTime"])
            val local = map[pair.members["localCandidateId"] as? String]
            candidateType = (local?.members?.get("candidateType") as? String).orEmpty()
            protocol = (local?.members?.get("protocol") as? String).orEmpty()
            val remote = map[pair.members["remoteCandidateId"] as? String]
            if ((remote?.members?.get("candidateType") as? String) == "relay") candidateType = "relay"
        }

        val video = map.values.firstOrNull {
            it.type == "inbound-rtp" && (it.members["kind"] == "video" || it.members["mediaType"] == "video")
        }
        if (video != null) {
            val m = video.members
            val ts = video.timestampUs / 1_000_000.0
            val bytes = long(m["bytesReceived"])
            val framesDecoded = long(m["framesDecoded"])
            val decodeTime = num(m["totalDecodeTime"])
            val jbDelay = num(m["jitterBufferDelay"])
            val jbEmitted = long(m["jitterBufferEmittedCount"])
            val lost = long(m["packetsLost"])
            val recv = long(m["packetsReceived"])
            val dt = ts - lastTs
            val bitrate = if (lastTs > 0 && dt > 0) ((bytes - lastBytes) * 8 / dt).toLong() else 0L
            val dFrames = framesDecoded - lastFramesDecoded
            val decodeMs = if (dFrames > 0) ((decodeTime - lastDecodeTime) / dFrames * 1000).toInt() else 0
            val dEmitted = jbEmitted - lastJbEmitted
            val jbMs = if (dEmitted > 0) ((jbDelay - lastJbDelay) / dEmitted * 1000).toInt() else 0
            val dLost = lost - lastPacketsLost
            val dRecv = recv - lastPacketsRecv
            val loss = if (dLost + dRecv > 0) dLost * 100f / (dLost + dRecv) else 0f
            map[m["codecId"] as? String]?.let { c ->
                codec = (c.members["mimeType"] as? String)?.substringAfter('/').orEmpty()
            }
            out = out.copy(
                fps = num(m["framesPerSecond"]).toInt(),
                bitrateBps = bitrate.coerceAtLeast(0),
                width = long(m["frameWidth"]).toInt(),
                height = long(m["frameHeight"]).toInt(),
                packetLossPct = loss.coerceAtLeast(0f),
                jitterMs = (num(m["jitter"]) * 1000).toInt(),
                decodeMs = decodeMs.coerceAtLeast(0),
                jitterBufferMs = jbMs.coerceAtLeast(0),
                framesDropped = long(m["framesDropped"]),
                decoder = (m["decoderImplementation"] as? String).orEmpty(),
            )
            lastTs = ts; lastBytes = bytes; lastFramesDecoded = framesDecoded; lastDecodeTime = decodeTime
            lastJbDelay = jbDelay; lastJbEmitted = jbEmitted; lastPacketsLost = lost; lastPacketsRecv = recv
        }
        return out.copy(rttMs = (rtt * 1000).toInt(), candidateType = candidateType, protocol = protocol, codec = codec)
    }

    private fun num(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun long(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }
}
