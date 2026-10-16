package com.webrtc_phone.android.core.net

import com.webrtc_phone.android.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * OkHttpClient 工厂：所有客户端派生自同一个根 client（共享连接池/线程池），
 * 每个服务器端点拥有自己的 TLS 信任配置（证书固定按端点隔离）。
 */
class HttpClientFactory(private val pinStore: CertificatePinStore) {

    private val root: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "WebrtcPhone-Android/${BuildConfig.VERSION_NAME}")
                    .build()
            )
        }
        .build()

    private val cache = ConcurrentHashMap<String, OkHttpClient>()

    /** REST 请求用 */
    fun forEndpoint(endpoint: ServerEndpoint): OkHttpClient =
        cache.getOrPut("http|" + endpoint.key + "|" + endpoint.secure) { build(endpoint, ws = false) }

    /** WebSocket 长连接用：无读超时 + 协议层 ping */
    fun webSocketFor(endpoint: ServerEndpoint): OkHttpClient =
        cache.getOrPut("ws|" + endpoint.key + "|" + endpoint.secure) { build(endpoint, ws = true) }

    /** 证书信任关系变化后需丢弃缓存的 TLS 会话 */
    fun invalidate(endpoint: ServerEndpoint) {
        cache.keys.filter { it.contains("|" + endpoint.key + "|") }.forEach { cache.remove(it) }
        root.connectionPool.evictAll()
    }

    private fun build(endpoint: ServerEndpoint, ws: Boolean): OkHttpClient {
        val b = root.newBuilder()
        if (ws) {
            b.readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS)
        }
        if (endpoint.secure) {
            val tm = PinningTrustManager(endpoint.key, pinStore)
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
            b.sslSocketFactory(ssl.socketFactory, tm).hostnameVerifier(tm.hostnameVerifier())
        }
        return b.build()
    }
}
