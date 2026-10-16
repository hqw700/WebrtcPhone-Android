package com.webrtc_phone.android.core.net

import java.net.URI

/**
 * 服务器地址（纯值对象，可单测）。
 *
 * 用户输入宽松：`192.168.1.2`、`host:8443`、`https://host`、`http://[::1]:8443/` 均可，
 * 统一规范为 scheme + host + port。未写协议默认 https，未写端口默认 8443（服务端默认端口）。
 */
data class ServerEndpoint(
    val secure: Boolean,
    val host: String,
    val port: Int,
) {
    /** 用于证书固定等场景的唯一键 */
    val key: String get() = "${hostForUrl()}:$port"

    val httpBase: String get() = "${if (secure) "https" else "http"}://${hostForUrl()}:$port"
    val wsBase: String get() = "${if (secure) "wss" else "ws"}://${hostForUrl()}:$port"

    /** 展示用：省略默认端口 */
    val display: String
        get() = buildString {
            append(if (secure) "https://" else "http://")
            append(hostForUrl())
            if (port != DEFAULT_PORT) append(':').append(port)
        }

    private fun hostForUrl(): String = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    fun url(path: String): String = httpBase + (if (path.startsWith("/")) path else "/$path")
    fun wsUrl(path: String): String = wsBase + (if (path.startsWith("/")) path else "/$path")

    /** 持久化格式即规范化 URL */
    fun serialize(): String = "${if (secure) "https" else "http"}://${hostForUrl()}:$port"

    companion object {
        const val DEFAULT_PORT = 8443

        /**
         * 解析用户输入；无法解析时返回 null。
         * 仅接受 http/https，拒绝带用户信息（user:pass@host）的地址，避免凭据混入 URL。
         */
        fun parse(input: String?): ServerEndpoint? {
            var raw = input?.trim().orEmpty()
            if (raw.isEmpty()) return null
            if (!raw.contains("://")) raw = "https://$raw"
            val uri = try {
                URI(raw)
            } catch (_: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            val secure = when (scheme) {
                "https", "wss" -> true
                "http", "ws" -> false
                else -> return null
            }
            if (uri.rawUserInfo != null) return null
            var host = uri.host ?: return null
            host = host.removePrefix("[").removeSuffix("]")
            if (host.isBlank()) return null
            val port = if (uri.port in 1..65535) uri.port else DEFAULT_PORT
            return ServerEndpoint(secure, host.lowercase(), port)
        }
    }
}
