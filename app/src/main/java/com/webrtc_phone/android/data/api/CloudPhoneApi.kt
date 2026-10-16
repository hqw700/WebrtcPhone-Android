package com.webrtc_phone.android.data.api

import android.net.Uri
import com.webrtc_phone.android.data.model.AdminUser
import com.webrtc_phone.android.data.model.AdminUserCreate
import com.webrtc_phone.android.data.model.CardRedeemResult
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.Me
import com.webrtc_phone.android.data.model.Parsers
import com.webrtc_phone.android.data.model.QuickText
import com.webrtc_phone.android.data.model.Share
import com.webrtc_phone.android.data.model.ShareCreateRequest
import com.webrtc_phone.android.data.model.ShareCreated
import com.webrtc_phone.android.data.model.TagsPayload
import com.webrtc_phone.android.data.model.objects
import org.json.JSONArray
import org.json.JSONObject

/** 信令服务器 REST 接口（对齐 webrtc-operator/standalone 路由表） */
class CloudPhoneApi(val client: ApiClient) {

    // ---------- 认证 ----------

    data class LoginResult(val token: String, val username: String, val role: String)

    suspend fun login(username: String, password: String): LoginResult {
        val body = client.post(
            "/api/login",
            JSONObject().put("username", username).put("password", password),
            auth = false
        )
        return ApiClient.parse {
            val o = JSONObject(body)
            LoginResult(
                token = o.getString("token"),
                username = o.optString("username", username),
                role = o.optString("role", "user"),
            )
        }
    }

    suspend fun logout() {
        runCatching { client.post("/api/logout", JSONObject()) }
    }

    suspend fun me(): Me = ApiClient.parse { Parsers.me(JSONObject(client.get("/api/me"))) }

    // ---------- 设备 ----------

    suspend fun devices(): List<Device> = ApiClient.parse { Parsers.devices(client.get("/devices")) }

    /** 删除离线设备档案（admin） */
    suspend fun deleteDevice(deviceId: String) {
        client.delete("/api/devices/" + Uri.encode(deviceId))
    }

    // ---------- 标签（admin） ----------

    suspend fun tags(): TagsPayload = ApiClient.parse { Parsers.tags(JSONObject(client.get("/api/tags"))) }

    suspend fun saveTags(payload: TagsPayload) {
        client.post("/api/tags", Parsers.tagsToJson(payload))
    }

    // ---------- 快捷文本 ----------

    suspend fun quickTexts(): List<QuickText> =
        ApiClient.parse { Parsers.quickTexts(JSONArray(client.get("/api/quick_texts"))) }

    suspend fun saveQuickTexts(list: List<QuickText>) {
        client.post("/api/quick_texts", Parsers.quickTextsToJson(list))
    }

    // ---------- 分享 / 卡密 ----------

    suspend fun createShare(req: ShareCreateRequest): ShareCreated {
        val o = client.postEnvelope(
            "/api/share/create",
            JSONObject()
                .put("device_id", req.deviceId)
                .put("expire_seconds", req.expireSeconds)
                .put("access_mode", req.accessMode.wire)
                .put("password", req.password)
                .put("description", req.description)
                .put("allow_clipboard", req.allowClipboard)
                .put("allow_file_tx", req.allowFileTx)
                .put("custom_card_code", req.customCardCode)
        )
        return ApiClient.parse { Parsers.shareCreated(o.getJSONObject("data")) }
    }

    suspend fun shares(deviceId: String? = null): List<Share> {
        val path = if (deviceId.isNullOrBlank()) "/api/share/list"
        else "/api/share/list?device_id=" + Uri.encode(deviceId)
        val o = client.getEnvelope(path)
        return ApiClient.parse {
            o.optJSONArray("data").objects().map(Parsers::share)
                .sortedByDescending { it.createdAt ?: 0L }
        }
    }

    suspend fun revokeShare(token: String) {
        client.postEnvelope("/api/share/revoke", JSONObject().put("token", token))
    }

    suspend fun extendShare(token: String, extendSeconds: Long) {
        client.postEnvelope(
            "/api/share/extend",
            JSONObject().put("token", token).put("extend_seconds", extendSeconds)
        )
    }

    /** 公开接口：卡密兑换（访客免登录） */
    suspend fun redeemCard(cardCode: String, password: String): CardRedeemResult {
        val body = JSONObject().put("card_code", cardCode.trim().uppercase())
        if (password.isNotEmpty()) body.put("password", password)
        val o = client.postEnvelope("/api/share/redeem_card", body, auth = false)
        return ApiClient.parse { Parsers.cardRedeem(o.getJSONObject("data")) }
    }

    fun shareLink(token: String): String = client.endpoint.url("/share?token=" + Uri.encode(token))

    // ---------- 用户管理（admin） ----------

    suspend fun users(): List<AdminUser> {
        // 无用户时 Go 编码 nil slice 为 "null"
        val text = client.get("/api/admin/users").trim()
        if (text.isEmpty() || text == "null") return emptyList()
        return ApiClient.parse { Parsers.adminUsers(JSONArray(text)) }
    }

    suspend fun createUser(req: AdminUserCreate) {
        client.post(
            "/api/admin/users/create",
            JSONObject()
                .put("username", req.username)
                .put("password", req.password)
                .put("role", req.role)
                .put("note", req.note)
                .put("expire_seconds", req.expireSeconds)
        )
    }

    suspend fun deleteUser(username: String) {
        client.post("/api/admin/users/delete", JSONObject().put("username", username))
    }

    suspend fun updateUserNote(username: String, note: String) {
        client.post("/api/admin/users/update_note", JSONObject().put("username", username).put("note", note))
    }

    suspend fun resetUserPassword(username: String, password: String) {
        client.post(
            "/api/admin/users/reset_password",
            JSONObject().put("username", username).put("password", password)
        )
    }

    /** 踢下线；deviceId 为空 = 踢掉该用户所有会话 */
    suspend fun kickUser(username: String, deviceId: String = "") {
        client.post("/api/admin/users/kick", JSONObject().put("username", username).put("device_id", deviceId))
    }

    /**
     * 更新账号有效期与能力策略。expireSeconds: -1 不变，0 永久，>0 从现在起算。
     */
    suspend fun updateUser(username: String, expireSeconds: Long, policy: com.webrtc_phone.android.data.model.UserPolicy?) {
        val body = JSONObject().put("username", username).put("expire_seconds", expireSeconds)
        if (policy != null) {
            body.put(
                "policy", JSONObject()
                    .put("forbid_bitrate", policy.forbidBitrate)
                    .put("forbid_fps", policy.forbidFps)
                    .put("forbid_resolution", policy.forbidResolution)
                    .put("forbid_audio", policy.forbidAudio)
                    .put("forbid_file_push", policy.forbidFilePush)
                    .put("forbid_terminal", policy.forbidTerminal)
                    .put("forbid_share", policy.forbidShare)
            )
        }
        client.post("/api/admin/users/update", body)
    }
}
