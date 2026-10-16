package com.webrtc_phone.android.core.net

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** 用户确认信任的一张自签证书 */
data class PinnedCertificate(
    val endpointKey: String,
    val sha256: String,
    val subject: String,
    val pinnedAt: Long,
)

/**
 * 证书固定存储（TOFU：首次连接由用户核对指纹后确认信任）。
 * 只固定叶子证书 SHA-256；证书变更后指纹不匹配会被拒绝并提示“证书已变更”，防中间人。
 */
class CertificatePinStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tls_pins", Context.MODE_PRIVATE)

    private val _pins = MutableStateFlow(load())
    val pins: StateFlow<List<PinnedCertificate>> = _pins.asStateFlow()

    fun fingerprintFor(endpointKey: String): String? =
        prefs.getString(KEY_PREFIX + endpointKey, null)?.substringBefore('|')

    @Synchronized
    fun pin(endpointKey: String, cert: UntrustedCertificateInfo) {
        prefs.edit()
            .putString(KEY_PREFIX + endpointKey, "${cert.sha256}|${System.currentTimeMillis()}|${cert.subject}")
            .apply()
        _pins.value = load()
    }

    @Synchronized
    fun remove(endpointKey: String) {
        prefs.edit().remove(KEY_PREFIX + endpointKey).apply()
        _pins.value = load()
    }

    private fun load(): List<PinnedCertificate> = prefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith(KEY_PREFIX) || v !is String) return@mapNotNull null
        val parts = v.split('|', limit = 3)
        PinnedCertificate(
            endpointKey = k.removePrefix(KEY_PREFIX),
            sha256 = parts[0],
            subject = parts.getOrElse(2) { "" },
            pinnedAt = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
        )
    }.sortedBy { it.endpointKey }

    companion object {
        private const val KEY_PREFIX = "pin:"

        fun sha256Of(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            return digest.joinToString(":") { "%02X".format(it) }
        }
    }
}
