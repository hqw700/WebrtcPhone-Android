package com.webrtc_phone.android.rtc

import org.json.JSONObject

/**
 * request-offer 的 scrcpy_options（字段与 Web 端 DeviceClient.vue 一致）。
 * 注意 video_source 两种模式都必须显式发送：agent 跨会话保留上次取值，
 * 普通模式缺省会残留 "camera"。
 */
object ScrcpyOptions {

    fun build(target: StreamTarget): JSONObject {
        val s = target.settings
        val o = JSONObject()
            .put("max_fps", s.maxFps)
            .put("max_size", s.maxSize)
            .put("bitrate", s.bitrateMbps * MBPS)
            .put("min_bitrate", s.minBitrateMbps * MBPS)
            .put("max_bitrate", s.maxBitrateMbps * MBPS)
            .put("bwe", s.bwe)
            .put("audio", s.audio)
            .put("audio_gain", 1)
            .put("audio_source", "output")
            .put("audio_dup", true)
            .put("audio_low_latency", false)
            .put("power_off", s.powerOff)

        when (val m = target.mode) {
            StreamMode.Screen -> o.put("video_source", "display")
            is StreamMode.Camera -> {
                val c = m.params
                o.put("video_source", "camera")
                    .put("camera_facing", c.facing)
                    .put("camera_id", c.cameraId)
                    .put("camera_size", c.size)
                    .put("camera_fps", c.fps)
                    .put("camera_high_speed", false)
                    .put("camera_ar", "16:9")
                    .put("camera_zoom", c.zoom)
                    .put("camera_orientation", "auto")
                    .put("camera_low_power", false)
                if (c.micAudio) {
                    o.put("audio", true).put("audio_source", "mic")
                }
            }
        }
        return o
    }

    private const val MBPS = 1_000_000L
}
