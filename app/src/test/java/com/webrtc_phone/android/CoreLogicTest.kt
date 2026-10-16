package com.webrtc_phone.android

import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.core.util.Formatters
import com.webrtc_phone.android.core.util.Rfc3339
import com.webrtc_phone.android.data.model.AccessMode
import com.webrtc_phone.android.data.model.Parsers
import com.webrtc_phone.android.data.model.TagsPayload
import com.webrtc_phone.android.data.model.Tag
import com.webrtc_phone.android.data.settings.QualityPreset
import com.webrtc_phone.android.data.settings.StreamSettings
import com.webrtc_phone.android.rtc.CameraParams
import com.webrtc_phone.android.rtc.ScrcpyOptions
import com.webrtc_phone.android.rtc.StreamAuth
import com.webrtc_phone.android.rtc.StreamMode
import com.webrtc_phone.android.rtc.StreamTarget
import com.webrtc_phone.android.rtc.channel.ClipboardProtocol
import com.webrtc_phone.android.rtc.channel.InputProtocol
import com.webrtc_phone.android.rtc.channel.TouchAction
import com.webrtc_phone.android.rtc.signaling.SignalingMessage
import com.webrtc_phone.android.rtc.signaling.SignalingProtocol
import com.webrtc_phone.android.ui.stream.TouchMapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEndpointTest {
    @Test fun defaults() {
        val e = ServerEndpoint.parse("192.168.1.2")!!
        assertTrue(e.secure); assertEquals(8443, e.port)
        assertEquals("wss://192.168.1.2:8443/connect_client", e.wsUrl("/connect_client"))
        assertEquals("https://192.168.1.2", e.display)
    }

    @Test fun httpAndPort() {
        val e = ServerEndpoint.parse("http://Host.Example:80/")!!
        assertFalse(e.secure); assertEquals("host.example", e.host); assertEquals(80, e.port)
    }

    @Test fun ipv6() {
        val e = ServerEndpoint.parse("https://[::1]:9000")!!
        assertEquals("https://[::1]:9000", e.httpBase)
    }

    @Test fun rejects() {
        assertNull(ServerEndpoint.parse(""))
        assertNull(ServerEndpoint.parse("ftp://x"))
        assertNull(ServerEndpoint.parse("https://u:p@host"))
    }

    @Test fun roundTrip() {
        val e = ServerEndpoint.parse("10.0.0.1:1234")!!
        assertEquals(e, ServerEndpoint.parse(e.serialize()))
    }
}

class TimeAndFormatTest {
    @Test fun rfc3339() {
        assertNull(Rfc3339.parse("0001-01-01T00:00:00Z"))
        assertEquals(0L, Rfc3339.parse("1970-01-01T00:00:00Z"))
        assertEquals(1500L, Rfc3339.parse("1970-01-01T00:00:01.500123456Z"))
        assertEquals(0L, Rfc3339.parse("1970-01-01T08:00:00+08:00"))
        assertNull(Rfc3339.parse("garbage"))
    }

    @Test fun formatters() {
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("1.5 KB", Formatters.bytes(1536))
        assertEquals("2天3小时", Formatters.duration(2 * 86400 + 3 * 3600))
        assertEquals("已到期", Formatters.duration(0))
        assertEquals("4.0 Mbps", Formatters.bitrate(4_000_000))
    }
}

class ParsersTest {
    @Test fun devices() {
        val body = """[{"device_id":"p1","online":true,"client_count":2,"screen_state":"off",
            "device_info":{"model":"Pixel","brand":"Google","displays":[{"x_res":1080,"y_res":2400}],
            "cameras":[{"id":"0","facing":"back","zoom":1.0}]},"my_lease_remaining_seconds":120}]"""
        val d = Parsers.devices(body).single()
        assertEquals("p1", d.id); assertTrue(d.online); assertTrue(d.inUse); assertTrue(d.screenOff)
        assertEquals("Google Pixel", d.info.title)
        assertEquals(1080, d.info.primaryDisplay!!.width)
        assertEquals(120L, d.myLeaseRemainingSeconds)
        assertEquals(1, d.info.cameras.size)
    }

    @Test fun devicesWrappedAndNull() {
        assertEquals(1, Parsers.devices("""{"devices":[{"device_id":"a"}]}""").size)
        assertTrue(Parsers.devices("null").isEmpty())
    }

    @Test fun tagsRoundTrip() {
        val p = TagsPayload(listOf(Tag("t1", "游戏", "#FF0000")), mapOf("d1" to listOf("t1"), "d2" to emptyList()))
        val back = Parsers.tags(Parsers.tagsToJson(p))
        assertEquals(p.tags, back.tags)
        assertEquals(mapOf("d1" to listOf("t1")), back.deviceTags)
    }

    @Test fun share() {
        val s = Parsers.share(JSONObject("""{"token_id":"st_1","card_code":"ABC","access_mode":"view_only",
            "expires_at":"0001-01-01T00:00:00Z","active_connections":1}"""))
        assertEquals("st_1", s.token); assertEquals(AccessMode.VIEW_ONLY, s.accessMode); assertNull(s.expiresAt)
    }
}

class ProtocolTest {
    private val sent = mutableListOf<String>()
    private var viewOnly = false
    private val input = InputProtocol({ sent += it; true }, { viewOnly }, clock = { 42L })

    @Test fun touch() {
        input.touch(1, TouchAction.CANCEL, 2000, -5, 1080, 1920)
        val o = JSONObject(sent.single())
        assertEquals("touch", o.getString("type"))
        assertEquals(TouchAction.UP, o.getInt("action"))
        assertEquals(1080, o.getInt("x")); assertEquals(0, o.getInt("y"))
        assertEquals(42L, o.getLong("client_ts_ms"))
    }

    @Test fun viewOnlyBlocks() {
        viewOnly = true
        assertFalse(input.press(3)); assertFalse(input.text("x")); assertTrue(sent.isEmpty())
    }

    @Test fun pressSendsDownUp() {
        input.press(4)
        assertEquals(listOf(0, 1), sent.map { JSONObject(it).getInt("action") })
    }

    @Test fun clipboardLoopGuard() {
        val out = mutableListOf<String>()
        val c = ClipboardProtocol({ out += it; true }, { true })
        assertTrue(c.push("hello"))
        assertFalse(c.push("hello"))
        assertNull(c.parseIncoming("""{"type":"clipboard","text":"hello"}"""))
        assertEquals("new", c.parseIncoming("""{"type":"clipboard","text":"new"}"""))
        assertNull(c.parseIncoming("""{"type":"clipboard","text":"kb","source":"keyboard"}"""))
    }

    @Test fun signaling() {
        val m = SignalingProtocol.parse("""{"message_type":"device_msg","payload":{"type":"ice-candidate",
            "candidate":{"candidate":"candidate:1","mid":"0","sdpMLineIndex":0}}}""")
        assertTrue(m is SignalingMessage.Candidate)
        assertEquals("0", (m as SignalingMessage.Candidate).candidate.sdpMid)
        val cfg = SignalingProtocol.parse("""{"message_type":"config","ice_servers":[{"urls":["stun:a"]},{"urls":"turn:b","username":"u","credential":"p"}]}""")
        assertEquals(2, (cfg as SignalingMessage.Config).iceServers.size)
        assertTrue(SignalingProtocol.parse("""{"type":"pong"}""") is SignalingMessage.Pong)
        val err = SignalingProtocol.parse("""{"message_type":"error","code":"SHARE_IN_USE","error":"x"}""") as SignalingMessage.Error
        assertEquals("SHARE_IN_USE", err.code)
    }

    @Test fun scrcpyOptions() {
        val ep = ServerEndpoint.parse("h")!!
        val base = StreamTarget(ep, "d", "d", StreamAuth.User("t"), settings = StreamSettings().withPreset(QualityPreset.HIGH))
        val o = ScrcpyOptions.build(base)
        assertEquals("display", o.getString("video_source"))
        assertEquals(6_000_000L, o.getLong("bitrate"))
        val cam = ScrcpyOptions.build(base.copy(mode = StreamMode.Camera(CameraParams(facing = "front", cameraId = "1"))))
        assertEquals("camera", cam.getString("video_source"))
        assertEquals("mic", cam.getString("audio_source"))
        assertEquals("1", cam.getString("camera_id"))
    }
}

class TouchMapperTest {
    @Test fun letterbox() {
        // 视图 1000x1000，视频 500x1000 → 内容区 x∈[250,750]
        val m = TouchMapper(1000, 1000, 500, 1000)
        assertNull(m.map(100f, 500f, clamp = false))
        assertEquals(250 to 500, m.map(500f, 500f, clamp = false))
        assertEquals(0 to 500, m.map(100f, 500f, clamp = true))
    }

    @Test fun invalid() {
        assertFalse(TouchMapper(0, 0, 100, 100).valid)
    }
}
