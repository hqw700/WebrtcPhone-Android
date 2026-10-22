package com.webrtc_phone.android

import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.core.util.Formatters
import com.webrtc_phone.android.core.util.Rfc3339
import com.webrtc_phone.android.data.model.AccessMode
import com.webrtc_phone.android.data.model.Parsers
import com.webrtc_phone.android.data.model.TagsPayload
import com.webrtc_phone.android.data.model.TagOps
import com.webrtc_phone.android.data.model.Tag
import com.webrtc_phone.android.data.settings.QualityPreset
import com.webrtc_phone.android.data.settings.StreamSettings
import com.webrtc_phone.android.rtc.CameraParams
import com.webrtc_phone.android.rtc.ws.WsProtocol
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
import com.webrtc_phone.android.ui.home.DeviceFilter
import com.webrtc_phone.android.ui.home.StatusFilter
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

    @Test fun tagOpsUpsertAddsAndReplaces() {
        val a = Tag("t1", "pve", "#3B82F6")
        val p0 = TagsPayload(listOf(a), emptyMap())
        val p1 = TagOps.upsert(p0, Tag("t2", "game", "#EF4444"))
        assertEquals(2, p1.tags.size)
        val p2 = TagOps.upsert(p1, Tag("t1", "pve-new", "#22C55E"))
        assertEquals(2, p2.tags.size)
        assertEquals("pve-new", p2.tags.first { it.id == "t1" }.name)
        assertEquals("#22C55E", p2.tags.first { it.id == "t1" }.color)
    }

    @Test fun tagOpsDeleteRemovesDefinitionAndMappings() {
        val p = TagsPayload(
            listOf(Tag("t1", "a", "#000"), Tag("t2", "b", "#111")),
            mapOf("d1" to listOf("t1", "t2"), "d2" to listOf("t1")),
        )
        val r = TagOps.delete(p, "t1")
        assertEquals(listOf("t2"), r.tags.map { it.id })
        assertEquals(listOf("t2"), r.deviceTags["d1"])
        assertNull(r.deviceTags["d2"])
    }

    @Test fun tagOpsSetDeviceTags() {
        val p = TagsPayload(listOf(Tag("t1", "a", "#000")), mapOf("d1" to listOf("t1")))
        val added = TagOps.setDeviceTags(p, "d2", listOf("t1"))
        assertEquals(listOf("t1"), added.deviceTags["d2"])
        val cleared = TagOps.setDeviceTags(added, "d1", emptyList())
        assertNull(cleared.deviceTags["d1"])
    }

    @Test fun tagOpsDuplicateName() {
        val tags = listOf(Tag("t1", "PVE", "#000"), Tag("t2", "Game", "#111"))
        assertTrue(TagOps.isDuplicateName(tags, "  pve ", selfId = null))
        assertFalse(TagOps.isDuplicateName(tags, "pve", selfId = "t1"))
        assertFalse(TagOps.isDuplicateName(tags, "new", selfId = null))
        assertFalse(TagOps.isDuplicateName(tags, "   ", selfId = null))
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

class DeviceFilterTest {
    private val tags = TagsPayload(
        tags = listOf(Tag("t1", "PVE", "#000"), Tag("t2", "Game", "#111")),
        deviceTags = mapOf("rk3588-aio" to listOf("t2")),
    )
    private fun dev(id: String, online: Boolean = true) =
        Parsers.devices("""[{"device_id":"$id","online":$online}]""").single()

    @Test fun searchMatchesId() {
        val f = DeviceFilter(query = "rk3588")
        assertTrue(f.matches(dev("rk3588-aio"), tags))
        assertFalse(f.matches(dev("cp-210"), tags))
    }

    @Test fun searchMatchesTagName() {
        // 搜 "game" 命中打了 game 标签的设备，未打标签的不命中
        val f = DeviceFilter(query = "game")
        assertTrue(f.matches(dev("rk3588-aio"), tags))
        assertFalse(f.matches(dev("cp-210"), tags))
    }

    @Test fun tagFilterChip() {
        val f = DeviceFilter(tagIds = setOf("t2"))
        assertTrue(f.matches(dev("rk3588-aio"), tags))
        assertFalse(f.matches(dev("cp-210"), tags))
    }

    @Test fun statusFilter() {
        assertFalse(DeviceFilter(status = StatusFilter.OFFLINE).matches(dev("x", online = true), tags))
        assertTrue(DeviceFilter(status = StatusFilter.OFFLINE).matches(dev("x", online = false), tags))
    }
}

class ServerRelayTest {
    @Test fun taskTypeByExtension() {
        assertEquals(com.webrtc_phone.android.data.model.ServerTaskType.INSTALL,
            com.webrtc_phone.android.data.model.ServerFileOps.taskTypeFor("app.APK"))
        assertEquals(com.webrtc_phone.android.data.model.ServerTaskType.PUSH_FILE,
            com.webrtc_phone.android.data.model.ServerFileOps.taskTypeFor("data.zip"))
    }

    @Test fun defaultDestPath() {
        assertEquals("/sdcard/Download/a.zip",
            com.webrtc_phone.android.data.model.ServerFileOps.defaultDestPath("a.zip"))
        // 去掉可能的目录成分
        assertEquals("/sdcard/Download/a.zip",
            com.webrtc_phone.android.data.model.ServerFileOps.defaultDestPath("x/y/a.zip"))
    }

    @Test fun absoluteUrl() {
        val ops = com.webrtc_phone.android.data.model.ServerFileOps
        assertEquals("https://h:8443/downloads/a?sig=1&exp=2",
            ops.absoluteUrl("https://h:8443", "/downloads/a?sig=1&exp=2"))
        // 已是绝对 URL 原样返回
        assertEquals("http://x/y", ops.absoluteUrl("https://h:8443", "http://x/y"))
    }

    @Test fun parseServerFiles() {
        val body = """[
            {"name":"b.zip","size":10,"updated_at":"1970-01-01T00:00:02Z","url":"/downloads/b.zip","owner":"admin"},
            {"name":"a.apk","size":20,"updated_at":"1970-01-01T00:00:05Z","url":"/downloads/a.apk"}
        ]"""
        val list = Parsers.serverFiles(body)
        assertEquals(2, list.size)
        // 按 updatedAt 降序：a.apk(5s) 在前
        assertEquals("a.apk", list.first().name)
        assertEquals(20L, list.first().size)
        assertTrue(Parsers.serverFiles("null").isEmpty())
    }

    @Test fun parseTaskDetails() {
        val o = JSONObject("""{"task_id":"t1","type":"push_file","devices":{
            "dev1":{"device_id":"dev1","status":"success","progress":100,"result":"ok"},
            "dev2":{"device_id":"dev2","status":"failed","progress":0,"result":"offline"}
        }}""")
        val d = Parsers.taskDetails(o)
        assertEquals("t1", d.taskId)
        assertTrue(d.devices["dev1"]!!.finished)
        assertTrue(d.devices["dev1"]!!.ok)
        assertTrue(d.devices["dev2"]!!.finished)
        assertFalse(d.devices["dev2"]!!.ok)
    }
}

class WsProtocolTest {
    private fun buildPrev(deviceId: String, isKey: Boolean, ptsUs: Long, nalu: ByteArray): ByteArray {
        val out = ByteArray(WsProtocol.PREV_HEADER_LEN + nalu.size)
        out[0] = 0x50; out[1] = 0x52; out[2] = 0x45; out[3] = 0x56 // PREV
        val idb = deviceId.toByteArray(Charsets.UTF_8)
        System.arraycopy(idb, 0, out, 4, minOf(idb.size, 32))
        out[36] = if (isKey) 1 else 0
        for (i in 0 until 8) out[37 + i] = ((ptsUs shr (8 * (7 - i))) and 0xff).toByte()
        val len = nalu.size
        out[45] = ((len shr 24) and 0xff).toByte()
        out[46] = ((len shr 16) and 0xff).toByte()
        out[47] = ((len shr 8) and 0xff).toByte()
        out[48] = (len and 0xff).toByte()
        System.arraycopy(nalu, 0, out, WsProtocol.PREV_HEADER_LEN, nalu.size)
        return out
    }

    @Test fun parsePrevRoundTrip() {
        val nalu = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00)
        val frame = buildPrev("dev-01", isKey = true, ptsUs = 123456789L, nalu = nalu)
        assertTrue(WsProtocol.isPrev(frame))
        val p = WsProtocol.parsePrev(frame)!!
        assertEquals("dev-01", p.deviceId)
        assertTrue(p.isKey)
        assertEquals(123456789L, p.ptsUs)
        assertTrue(nalu.contentEquals(p.nalu))
    }

    @Test fun parsePrevRejectsShortOrWrongMagic() {
        assertNull(WsProtocol.parsePrev(ByteArray(10)))
        // 正确长度但 magic 是 AUDO
        val audo = ByteArray(WsProtocol.PREV_HEADER_LEN).also { it[0] = 'A'.code.toByte(); it[1] = 'U'.code.toByte(); it[2] = 'D'.code.toByte(); it[3] = 'O'.code.toByte() }
        assertFalse(WsProtocol.isPrev(audo))
        assertNull(WsProtocol.parsePrev(audo))
    }

    @Test fun parsePrevRejectsTruncatedPayload() {
        // 声称 payloadLen=100 但实际没有那么多字节
        val f = buildPrev("d", false, 0, ByteArray(4))
        f[45] = 0; f[46] = 0; f[47] = 0; f[48] = 100
        assertNull(WsProtocol.parsePrev(f))
    }

    @Test fun startPreviewJson() {
        val o = JSONObject(WsProtocol.startPreview("dev-9", fps = 60, maxSize = 1080, bitrateBps = 4_000_000, stayAwake = true))
        assertEquals("start_preview", o.getString("message_type"))
        assertEquals("dev-9", o.getString("device_id"))
        assertEquals(60, o.getInt("fps"))
        assertEquals(1080, o.getInt("max_size"))
        assertEquals(4_000_000, o.getInt("bitrate"))
        assertTrue(o.getBoolean("stay_awake"))
    }

    @Test fun stopPreviewJson() {
        val o = JSONObject(WsProtocol.stopPreview("dev-9"))
        assertEquals("stop_preview", o.getString("message_type"))
        assertEquals("dev-9", o.getString("device_id"))
    }

    @Test fun groupControlEnvelope() {
        val event = JSONObject().put("type", "touch").put("action", 0).put("x", 10).put("y", 20)
        val o = JSONObject(WsProtocol.groupControlEvent("dev-7", event))
        assertEquals("group_control_event", o.getString("message_type"))
        assertEquals("dev-7", o.getJSONArray("target_device_ids").getString(0))
        assertEquals("touch", o.getJSONObject("event").getString("type"))
        assertEquals(10, o.getJSONObject("event").getInt("x"))
    }
}

class AdminLeaseAndUsersTest {
    @Test fun parseAdminLease() {
        val json = JSONObject("""{
            "lease_id": "lease_abc123",
            "username": "tester",
            "device_id": "pixel_7",
            "created_at": "2026-10-21T10:00:00Z",
            "expires_at": "2026-10-22T10:00:00Z",
            "remaining_seconds": 86400,
            "active": true,
            "note": "订单号 #8888",
            "created_by": "admin"
        }""")
        val l = Parsers.adminLease(json)
        assertEquals("lease_abc123", l.leaseId)
        assertEquals("tester", l.username)
        assertEquals("pixel_7", l.deviceId)
        assertEquals(86400L, l.remainingSeconds)
        assertTrue(l.active)
        assertFalse(l.isPermanent)
        assertFalse(l.isRevoked)
        assertFalse(l.isExpiringSoon(thresholdSeconds = 3600)) // 86400 不在 1 小时内
        assertTrue(l.isExpiringSoon(thresholdSeconds = 86400 * 2)) // 在 2 天内
        assertEquals("订单号 #8888", l.note)
    }

    @Test fun usersUiStateDerivedProps() {
        val dev1 = com.webrtc_phone.android.data.model.Device(id = "d1", online = true)
        val dev2 = com.webrtc_phone.android.data.model.Device(id = "d2", online = true)
        val dev3 = com.webrtc_phone.android.data.model.Device(id = "d3", online = false)

        val u1 = com.webrtc_phone.android.data.model.AdminUser("user1", "user", "", true, listOf("d1"), null, com.webrtc_phone.android.data.model.UserPolicy())
        val u2 = com.webrtc_phone.android.data.model.AdminUser("admin", "admin", "主管", true, emptyList(), null, com.webrtc_phone.android.data.model.UserPolicy())

        val l1 = com.webrtc_phone.android.data.model.AdminLease("l1", "user1", "d1", null, null, 3600, true, "", "admin", null)
        val l2 = com.webrtc_phone.android.data.model.AdminLease("l2", "user1", "d2", null, null, 0, false, "", "admin", 123L) // 已失效/收回

        val st = com.webrtc_phone.android.ui.manage.UsersUiState(
            users = listOf(u1, u2),
            leases = listOf(l1, l2),
            devices = listOf(dev1, dev2, dev3)
        )

        assertEquals(2, st.totalUsers)
        assertEquals(2, st.onlineUsers)
        assertEquals(1, st.adminUsers)
        assertEquals(1, st.totalActiveLeases)

        // d1 处于活跃租约，d2 租约已失效，所以 idleDevices 为 d2 和 d3
        assertEquals(setOf("d1"), st.leasedDeviceIds)
        assertEquals(listOf(dev2, dev3), st.idleDevices)
        assertEquals(1, st.activeLeasesByUser["user1"]?.size)
        assertEquals(2, st.allLeasesByUser["user1"]?.size)
    }
}
