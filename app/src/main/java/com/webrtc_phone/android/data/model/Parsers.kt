package com.webrtc_phone.android.data.model

import com.webrtc_phone.android.core.util.Rfc3339
import org.json.JSONArray
import org.json.JSONObject

/**
 * 服务端 JSON → 领域模型。全部容错解析（字段缺失给默认值），
 * 服务端新增字段不影响客户端，老服务端缺字段也不崩溃。
 */
object Parsers {

    fun me(o: JSONObject) = Me(
        username = o.optString("username"),
        role = o.optString("role", "user"),
        expiresAt = Rfc3339.parse(o.optStringOrNull("expires_at")),
        policy = policy(o),
        leases = o.optJSONArray("leases").objects().map {
            Lease(
                deviceId = it.optString("device_id"),
                expiresAt = Rfc3339.parse(it.optStringOrNull("expires_at")),
                remainingSeconds = it.optLong("remaining_seconds", -1),
            )
        },
    )

    fun policy(o: JSONObject) = UserPolicy(
        forbidBitrate = o.optBoolean("forbid_bitrate"),
        forbidFps = o.optBoolean("forbid_fps"),
        forbidResolution = o.optBoolean("forbid_resolution"),
        forbidAudio = o.optBoolean("forbid_audio"),
        forbidFilePush = o.optBoolean("forbid_file_push"),
        forbidTerminal = o.optBoolean("forbid_terminal"),
        forbidShare = o.optBoolean("forbid_share"),
    )

    fun deviceInfo(o: JSONObject?): DeviceInfo {
        if (o == null) return DeviceInfo()
        return DeviceInfo(
            model = o.optString("model"),
            brand = o.optString("brand"),
            androidVersion = o.optString("android_version"),
            sdkInt = o.opt("sdk_int")?.toString().orEmpty(),
            cpuAbi = o.optString("cpu_abi"),
            memTotalMb = o.optLong("mem_total_mb"),
            isEmulator = o.optBoolean("is_emulator"),
            agentVersion = o.optString("agent_version", o.optString("app_version")),
            cameraSupport = o.optBoolean("camera_support"),
            cameras = o.optJSONArray("cameras").objects().map {
                CameraLens(
                    id = it.optString("id", "0"),
                    facing = it.optString("facing", "back"),
                    type = it.optString("type"),
                    name = it.optString("name"),
                    zoom = it.optDouble("zoom", 1.0).takeIf { z -> !z.isNaN() } ?: 1.0,
                )
            },
            displays = o.optJSONArray("displays").objects().mapNotNull {
                val w = it.optInt("x_res")
                val h = it.optInt("y_res")
                if (w > 0 && h > 0) DisplayInfo(w, h) else null
            },
        )
    }

    fun device(o: JSONObject): Device {
        val lease = o.optJSONObject("lease")
        return Device(
            id = o.optString("device_id", o.optString("id")),
            info = deviceInfo(o.optJSONObject("device_info")),
            online = o.optBoolean("online", false),
            screenState = o.optString("screen_state"),
            lastSeen = Rfc3339.parse(o.optStringOrNull("last_seen")),
            clientCount = o.optInt("client_count"),
            clients = o.optJSONArray("clients").objects().map {
                DeviceClient(
                    name = it.optString("name"),
                    kind = it.optString("kind", "user"),
                    remainingSeconds = it.optLong("remaining_seconds", -1),
                    count = it.optInt("count", 1),
                )
            },
            myLeaseRemainingSeconds = if (o.has("my_lease_remaining_seconds") && !o.isNull("my_lease_remaining_seconds"))
                o.optLong("my_lease_remaining_seconds") else null,
            leaseHolder = lease?.optString("username")?.takeIf { it.isNotBlank() },
        )
    }

    /** /devices 兼容数组与 {devices:[...]} 两种格式 */
    fun devices(body: String): List<Device> {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed == "null") return emptyList()
        val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
        else JSONObject(trimmed).optJSONArray("devices")
        return devices(arr)
    }

    fun devices(arr: JSONArray?): List<Device> = arr.objects().map(::device).filter { it.id.isNotBlank() }

    fun tags(o: JSONObject): TagsPayload {
        val tags = o.optJSONArray("tags").objects().map {
            Tag(it.optString("id"), it.optString("name"), it.optString("color", "#3B82F6"))
        }
        val map = mutableMapOf<String, List<String>>()
        o.optJSONObject("deviceTags")?.let { dt ->
            for (k in dt.keys()) map[k] = dt.optJSONArray(k).strings()
        }
        return TagsPayload(tags, map)
    }

    fun tagsToJson(p: TagsPayload): JSONObject = JSONObject().apply {
        put("tags", JSONArray().apply {
            p.tags.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
        })
        put("deviceTags", JSONObject().apply {
            p.deviceTags.forEach { (dev, ids) -> if (ids.isNotEmpty()) put(dev, JSONArray(ids)) }
        })
    }

    fun quickTexts(arr: JSONArray?): List<QuickText> = arr.objects().map {
        QuickText(
            id = it.optString("id"),
            title = it.optString("title"),
            content = it.optString("content"),
            autoEnter = it.optBoolean("auto_enter"),
            createdAt = it.optLong("created_at"),
        )
    }

    fun quickTextsToJson(list: List<QuickText>): JSONArray = JSONArray().apply {
        list.forEach {
            put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("content", it.content)
                    .put("auto_enter", it.autoEnter)
                    .put("created_at", it.createdAt)
            )
        }
    }

    fun share(o: JSONObject) = Share(
        token = o.optString("token_id", o.optString("token")),
        cardCode = o.optString("card_code"),
        deviceId = o.optString("device_id"),
        creator = o.optString("creator"),
        createdAt = Rfc3339.parse(o.optStringOrNull("created_at")),
        expiresAt = Rfc3339.parse(o.optStringOrNull("expires_at")),
        accessMode = AccessMode.of(o.optString("access_mode")),
        requirePassword = o.optBoolean("require_password"),
        allowClipboard = o.optBoolean("allow_clipboard"),
        allowFileTx = o.optBoolean("allow_file_tx"),
        description = o.optString("description"),
        useCount = o.optLong("use_count"),
        lastUsedAt = Rfc3339.parse(o.optStringOrNull("last_used_at")),
        activeConnections = o.optInt("active_connections"),
    )

    fun shareCreated(o: JSONObject) = ShareCreated(
        token = o.optString("token"),
        cardCode = o.optString("card_code"),
        shareUrl = o.optString("share_url"),
        expiresAt = Rfc3339.parse(o.optStringOrNull("expires_at")),
    )

    fun adminUsers(arr: JSONArray?): List<AdminUser> = arr.objects().map {
        AdminUser(
            username = it.optString("username"),
            role = it.optString("role", "user"),
            note = it.optString("note"),
            online = it.optBoolean("online"),
            activeDevices = it.optJSONArray("active_devices").strings(),
            expiresAt = Rfc3339.parse(it.optStringOrNull("expires_at")),
            policy = policy(it),
        )
    }

    /** /api/files 返回数组 [{name,size,updated_at,url,owner,uploaded_at}] */
    fun serverFiles(body: String): List<ServerFile> {
        val t = body.trim()
        if (t.isEmpty() || t == "null") return emptyList()
        val arr = JSONArray(t)
        return arr.objects().map {
            ServerFile(
                name = it.optString("name"),
                size = it.optLong("size"),
                updatedAt = Rfc3339.parse(it.optStringOrNull("updated_at")) ?: 0L,
                url = it.optString("url"),
                owner = it.optString("owner"),
            )
        }.filter { it.name.isNotBlank() }.sortedByDescending { it.updatedAt }
    }

    /** /api/tasks/details 返回 BatchTask：{task_id,type,devices:{id:{device_id,status,progress,result}}} */
    fun taskDetails(o: JSONObject): TaskDetails {
        val devs = mutableMapOf<String, SubTaskStatus>()
        o.optJSONObject("devices")?.let { d ->
            for (k in d.keys()) {
                val s = d.optJSONObject(k) ?: continue
                devs[k] = SubTaskStatus(
                    deviceId = s.optString("device_id", k),
                    status = s.optString("status"),
                    progress = s.optInt("progress"),
                    result = s.optString("result"),
                )
            }
        }
        return TaskDetails(
            taskId = o.optString("task_id"),
            type = o.optString("type"),
            devices = devs,
        )
    }
}

// ---------- org.json 小工具 ----------

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it, null)?.takeIf { s -> s.isNotEmpty() } }
}

/** optString 对 null 字段返回 "null"，这里统一转成真正的 null */
fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)
