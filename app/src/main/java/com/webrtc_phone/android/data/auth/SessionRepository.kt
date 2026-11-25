package com.webrtc_phone.android.data.auth

import android.content.Context
import com.webrtc_phone.android.core.net.HttpClientFactory
import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.core.security.SecureStore
import com.webrtc_phone.android.data.api.ApiClient
import com.webrtc_phone.android.data.api.CloudPhoneApi
import com.webrtc_phone.android.data.model.AuthSession
import com.webrtc_phone.android.data.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 登录态与服务器档案。
 * - token 存 SecureStore（Keystore 加密），绝不写入普通 prefs / 日志 / URL；
 * - 最近使用的服务器 + 用户名（非敏感）存普通 prefs，用于登录页快捷选择；
 * - 收到 401 时自动登出，UI 订阅 [session] 即可跳回登录页。
 */
class SessionRepository(
    context: Context,
    private val secureStore: SecureStore,
    private val httpFactory: HttpClientFactory,
) {
    private val prefs = context.getSharedPreferences("session_meta", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(restore())
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    /** 登出原因（如 token 过期），登录页展示一次后清空 */
    private val _logoutReason = MutableStateFlow<String?>(null)
    val logoutReason: StateFlow<String?> = _logoutReason.asStateFlow()

    @Volatile
    private var cachedApi: Pair<AuthSession, CloudPhoneApi>? = null

    /** 当前会话的 API；未登录返回 null */
    fun api(): CloudPhoneApi? {
        val s = _session.value ?: return null
        cachedApi?.let { (cs, api) -> if (cs == s) return api }
        val ep = ServerEndpoint.parse(s.serverUrl) ?: return null
        val api = CloudPhoneApi(
            ApiClient(
                httpFactory, ep,
                tokenProvider = { _session.value?.token },
                onUnauthorized = { expire("登录已过期，请重新登录") },
            )
        )
        cachedApi = s to api
        return api
    }

    /** 未登录场景（登录、卡密兑换、单机直连）使用的匿名 API */
    fun anonymousApi(endpoint: ServerEndpoint): CloudPhoneApi =
        CloudPhoneApi(ApiClient(httpFactory, endpoint, tokenProvider = { null }))

    data class StandaloneConnectResult(
        val endpoint: ServerEndpoint,
        val token: String,
        val device: Device,
    )

    /**
     * 免信令单机直连握手：
     * 1) 向被控手机内置 Agent 的 POST /api/login 校验可选直连密码并换取会话 Token；
     * 2) 用该 Token 拉取 GET /devices 获取真实设备 ID、显示名称、分辨率与多摄信息。
     */
    suspend fun connectStandalone(endpoint: ServerEndpoint, password: String): StandaloneConnectResult {
        val loginRes = anonymousApi(endpoint).login("standalone", password)
        val authedApi = CloudPhoneApi(
            ApiClient(httpFactory, endpoint, tokenProvider = { loginRes.token })
        )
        val dev = runCatching { authedApi.devices().firstOrNull() }.getOrNull()
            ?: Device(id = endpoint.key, alias = endpoint.host, online = true)
        rememberDirectHost(endpoint, dev.displayName)
        return StandaloneConnectResult(endpoint = endpoint, token = loginRes.token, device = dev)
    }

    suspend fun login(endpoint: ServerEndpoint, username: String, password: String): AuthSession {
        val result = anonymousApi(endpoint).login(username, password)
        val s = AuthSession(endpoint.serialize(), result.token, result.username, result.role)
        persist(s)
        rememberServer(endpoint, result.username)
        _logoutReason.value = null
        _session.value = s
        return s
    }

    suspend fun logout() {
        api()?.logout()
        clear()
    }

    fun expire(reason: String) {
        if (_session.value == null) return
        _logoutReason.value = reason
        clear()
    }

    fun consumeLogoutReason() {
        _logoutReason.value = null
    }

    private fun clear() {
        secureStore.remove(KEY_SESSION)
        cachedApi = null
        _session.value = null
    }

    private fun persist(s: AuthSession) {
        secureStore.putString(
            KEY_SESSION,
            JSONObject()
                .put("server", s.serverUrl)
                .put("token", s.token)
                .put("username", s.username)
                .put("role", s.role)
                .toString()
        )
    }

    private fun restore(): AuthSession? = runCatching {
        val raw = secureStore.getString(KEY_SESSION) ?: return null
        val o = JSONObject(raw)
        AuthSession(o.getString("server"), o.getString("token"), o.getString("username"), o.optString("role", "user"))
    }.getOrNull()

    // ---------- 最近服务器 ----------

    data class RecentServer(val url: String, val username: String)

    fun recentServers(): List<RecentServer> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_RECENT, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { RecentServer(it.optString("url"), it.optString("username")) }
        }.filter { it.url.isNotBlank() }
    }.getOrDefault(emptyList())

    fun rememberServer(endpoint: ServerEndpoint, username: String) {
        val url = endpoint.serialize()
        val list = listOf(RecentServer(url, username)) +
            recentServers().filter { it.url != url }
        val arr = JSONArray()
        list.take(MAX_RECENT).forEach { arr.put(JSONObject().put("url", it.url).put("username", it.username)) }
        prefs.edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    fun forgetServer(url: String) {
        // 移除最近服务器时一并删除该账号保存的密码
        recentServers().filter { it.url == url }.forEach { clearPassword(url, it.username) }
        val arr = JSONArray()
        recentServers().filter { it.url != url }
            .forEach { arr.put(JSONObject().put("url", it.url).put("username", it.username)) }
        prefs.edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    // ---------- 记住密码（Keystore 加密，按 服务器+用户名 隔离） ----------

    fun savedPassword(url: String, username: String): String? =
        if (username.isBlank()) null else secureStore.getString(passwordKey(url, username))

    fun savePassword(url: String, username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) return
        secureStore.putString(passwordKey(url, username), password)
    }

    fun clearPassword(url: String, username: String) {
        secureStore.remove(passwordKey(url, username))
    }

    /** “记住密码”勾选状态（非敏感，普通 prefs） */
    var rememberPassword: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, true)
        set(v) {
            prefs.edit().putBoolean(KEY_REMEMBER, v).apply()
        }

    private fun passwordKey(url: String, username: String) = "pwd:$url|$username"

    // ---------- 最近单机直连地址与密码（密码仅存 SecureStore） ----------

    data class RecentDirectHost(val url: String, val label: String)

    fun recentDirectHosts(): List<RecentDirectHost> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_RECENT_DIRECT, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { RecentDirectHost(it.optString("url"), it.optString("label")) }
        }.filter { it.url.isNotBlank() }
    }.getOrDefault(emptyList())

    fun rememberDirectHost(endpoint: ServerEndpoint, label: String) {
        val url = endpoint.serialize()
        val list = listOf(RecentDirectHost(url, label)) +
            recentDirectHosts().filter { it.url != url }
        val arr = JSONArray()
        list.take(MAX_RECENT).forEach { arr.put(JSONObject().put("url", it.url).put("label", it.label)) }
        prefs.edit().putString(KEY_RECENT_DIRECT, arr.toString()).apply()
    }

    fun forgetDirectHost(url: String) {
        clearDirectPassword(url)
        val arr = JSONArray()
        recentDirectHosts().filter { it.url != url }
            .forEach { arr.put(JSONObject().put("url", it.url).put("label", it.label)) }
        prefs.edit().putString(KEY_RECENT_DIRECT, arr.toString()).apply()
    }

    fun savedDirectPassword(url: String): String? =
        if (url.isBlank()) null else secureStore.getString(directPasswordKey(url))

    fun saveDirectPassword(url: String, password: String) {
        if (url.isBlank()) return
        if (password.isEmpty()) clearDirectPassword(url)
        else secureStore.putString(directPasswordKey(url), password)
    }

    fun clearDirectPassword(url: String) {
        if (url.isNotBlank()) secureStore.remove(directPasswordKey(url))
    }

    private fun directPasswordKey(url: String) = "direct_pwd:$url"

    companion object {
        private const val KEY_SESSION = "auth_session"
        private const val KEY_RECENT = "recent_servers"
        private const val KEY_RECENT_DIRECT = "recent_direct_hosts"
        private const val KEY_REMEMBER = "remember_password"
        private const val MAX_RECENT = 6
    }
}
