package com.webrtc_phone.android.rtc.signaling

import org.json.JSONArray
import org.json.JSONObject

data class IceServerSpec(val urls: List<String>, val username: String?, val credential: String?)

data class CandidateSpec(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String)

/** 信令入站消息（与 webrtc-operator /connect_client 协议对应） */
sealed interface SignalingMessage {
    data class Config(val iceServers: List<IceServerSpec>) : SignalingMessage
    data class DeviceInfo(val info: JSONObject) : SignalingMessage
    data class Offer(val sdp: String) : SignalingMessage
    data class Answer(val sdp: String) : SignalingMessage
    data class Candidate(val candidate: CandidateSpec) : SignalingMessage
    data class Error(val code: String, val message: String) : SignalingMessage
    data object Pong : SignalingMessage
    data class Other(val type: String) : SignalingMessage
}

/** 纯 JSON 编解码，可单测 */
object SignalingProtocol {

    fun parse(text: String): SignalingMessage? {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val type = msg.optString("message_type").ifEmpty { msg.optString("type") }
        return when (type) {
            "pong" -> SignalingMessage.Pong
            "config" -> SignalingMessage.Config(parseIceServers(msg.optJSONArray("ice_servers")))
            "device_info" -> SignalingMessage.DeviceInfo(msg.optJSONObject("device_info") ?: JSONObject())
            "device_msg" -> msg.optJSONObject("payload")?.let(::parsePayload)
            "error" -> SignalingMessage.Error(
                code = msg.optString("code"),
                message = msg.optString("error").ifBlank { msg.optString("msg", "服务器错误") },
            )
            // 兼容直接下发的 offer/answer/candidate
            "offer", "answer", "ice-candidate" -> parsePayload(msg)
            else -> SignalingMessage.Other(type)
        }
    }

    private fun parsePayload(p: JSONObject): SignalingMessage? = when (p.optString("type")) {
        "offer" -> p.optString("sdp").takeIf { it.isNotEmpty() }?.let { SignalingMessage.Offer(it) }
        "answer" -> p.optString("sdp").takeIf { it.isNotEmpty() }?.let { SignalingMessage.Answer(it) }
        "ice-candidate" -> {
            val c = p.optJSONObject("candidate") ?: p
            val sdp = c.optString("candidate")
            if (sdp.isEmpty()) null
            else SignalingMessage.Candidate(
                CandidateSpec(
                    sdpMid = c.optString("sdpMid").ifEmpty { c.optString("mid") }.ifEmpty { null },
                    sdpMLineIndex = c.optInt("sdpMLineIndex", 0),
                    candidate = sdp,
                )
            )
        }
        else -> null
    }

    fun parseIceServers(arr: JSONArray?): List<IceServerSpec> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val urls = when (val u = o.opt("urls") ?: o.opt("url")) {
                is JSONArray -> (0 until u.length()).mapNotNull { u.optString(it).takeIf { s -> s.isNotBlank() } }
                is String -> listOf(u)
                else -> emptyList()
            }
            if (urls.isEmpty()) null
            else IceServerSpec(
                urls,
                o.optString("username").ifEmpty { null },
                o.optString("credential").ifEmpty { null },
            )
        }
    }

    fun connect(deviceId: String): String =
        JSONObject().put("message_type", "connect").put("device_id", deviceId).toString()

    fun forward(deviceId: String, payload: JSONObject): String =
        JSONObject().put("message_type", "forward").put("device_id", deviceId).put("payload", payload).toString()

    fun requestOffer(deviceId: String, scrcpyOptions: JSONObject): String = forward(
        deviceId,
        JSONObject().put("type", "request-offer").put("ip_preference", "auto").put("scrcpy_options", scrcpyOptions)
    )

    fun answer(deviceId: String, sdp: String): String =
        forward(deviceId, JSONObject().put("type", "answer").put("sdp", sdp))

    fun candidate(deviceId: String, c: CandidateSpec): String = forward(
        deviceId,
        JSONObject().put("type", "ice-candidate").put(
            "candidate",
            JSONObject()
                .put("candidate", c.candidate)
                .put("sdpMLineIndex", c.sdpMLineIndex)
                .put("sdpMid", c.sdpMid)
                .put("mid", c.sdpMid)
        )
    )

    const val PING = "{\"type\":\"ping\"}"
}
