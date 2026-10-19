package com.webrtc_phone.android.rtc.ws

import org.json.JSONArray
import org.json.JSONObject

/**
 * WS 模式（WebSocket H.264 裸流中转投屏）协议编解码，纯逻辑、无 Android 依赖，便于单测。
 * 协议对齐 web-app（`useWebSocketStream.js` / `stores/devices.js`），而非旧的 cloudphone-host 参考。
 *
 * 信令与媒体同走 /connect_client：
 *  - 文本 JSON：start_preview / stop_preview 控制；group_control_event 下发直控事件；
 *  - 二进制帧：49 字节 PREV 头 + H.264 Annex-B 裸流。
 */
object WsProtocol {

    const val PREV_HEADER_LEN = 49
    private const val DEVICE_ID_LEN = 32

    /** 解析出的 PREV 视频帧（nalu 为 Annex-B 裸流切片引用的拷贝） */
    data class PrevFrame(val deviceId: String, val isKey: Boolean, val ptsUs: Long, val nalu: ByteArray)

    /**
     * 是否为 PREV 二进制帧（magic "PREV"）。音频帧为 "AUDO"，web-app WS 模式当前不含音频。
     */
    fun isPrev(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x52.toByte() &&
            data[2] == 0x45.toByte() && data[3] == 0x56.toByte()

    /**
     * 解析 PREV 帧。头部（大端）：
     *  [0:4]   magic "PREV"
     *  [4:36]  device_id（32 字节，\0 右填充）
     *  [36]    关键帧标志（1=key）
     *  [37:45] ptsUs，uint64 BE
     *  [45:49] payloadLen，uint32 BE
     *  [49:49+payloadLen] H.264 Annex-B NALU
     * 非法/长度不足返回 null。
     */
    fun parsePrev(data: ByteArray): PrevFrame? {
        if (data.size < PREV_HEADER_LEN || !isPrev(data)) return null
        val idEnd = run {
            var e = 4
            val limit = 4 + DEVICE_ID_LEN
            while (e < limit && data[e].toInt() != 0) e++
            e
        }
        val deviceId = String(data, 4, idEnd - 4, Charsets.UTF_8)
        val isKey = data[36].toInt() == 1
        val ptsUs = readU64BE(data, 37)
        val payloadLen = readU32BE(data, 45)
        if (payloadLen < 0 || data.size < PREV_HEADER_LEN + payloadLen) return null
        val nalu = data.copyOfRange(PREV_HEADER_LEN, PREV_HEADER_LEN + payloadLen)
        return PrevFrame(deviceId, isKey, ptsUs, nalu)
    }

    private fun readU32BE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)

    private fun readU64BE(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xff)
        return v
    }

    // ---------- 信令控制 ----------

    /**
     * start_preview。bitrate 语义对齐 web：>=10000 视为 bps 原样下发；否则按 Mbps 乘 1_000_000。
     * 这里统一传入 bps（调用方用 StreamSettings.bitrate），直接下发。
     */
    fun startPreview(deviceId: String, fps: Int, maxSize: Int, bitrateBps: Int, stayAwake: Boolean): String =
        JSONObject().apply {
            put("message_type", "start_preview")
            put("type", "start_preview")
            put("device_id", deviceId)
            if (fps > 0) put("fps", fps)
            if (maxSize > 0) put("max_size", maxSize)
            if (bitrateBps > 0) put("bitrate", bitrateBps)
            put("stay_awake", stayAwake)
        }.toString()

    fun stopPreview(deviceId: String): String =
        JSONObject().apply {
            put("message_type", "stop_preview")
            put("type", "stop_preview")
            put("device_id", deviceId)
        }.toString()

    /**
     * 把一个直控事件（{type:"touch"|"inject_keycode"|...}）包进 group_control_event 下发信封。
     * WS 模式下所有输入都走这条，而不是 WebRTC 的 input-channel DataChannel。
     */
    fun groupControlEvent(deviceId: String, event: JSONObject): String =
        JSONObject().apply {
            put("message_type", "group_control_event")
            put("target_device_ids", JSONArray().put(deviceId))
            put("event", event)
        }.toString()
}
