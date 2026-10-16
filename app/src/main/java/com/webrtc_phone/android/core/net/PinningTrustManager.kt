package com.webrtc_phone.android.core.net

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** 服务器证书不被系统信任且未被用户固定时抛出，携带供用户核对的证书信息 */
data class UntrustedCertificateInfo(
    val endpointKey: String,
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notAfter: Long,
    /** true = 之前固定过但指纹变了（可能遭遇中间人或服务器换证书） */
    val changed: Boolean,
)

class UntrustedCertificateException(val info: UntrustedCertificateInfo, cause: Throwable?) :
    CertificateException("Untrusted server certificate for ${info.endpointKey}", cause)

/**
 * 每个服务器端点一个实例：先走系统 CA 校验；失败时仅当叶子证书指纹与用户固定的一致才放行。
 * 绝不存在“信任所有证书”的路径。
 */
class PinningTrustManager(
    private val endpointKey: String,
    private val pinStore: CertificatePinStore,
) : X509TrustManager {

    private val system: X509TrustManager = run {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        system.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
            return
        } catch (e: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw e
            val fp = CertificatePinStore.sha256Of(leaf)
            val pinned = pinStore.fingerprintFor(endpointKey)
            if (pinned != null && pinned.equals(fp, ignoreCase = true)) {
                leaf.checkValidity()
                return
            }
            throw UntrustedCertificateException(
                UntrustedCertificateInfo(
                    endpointKey = endpointKey,
                    sha256 = fp,
                    subject = leaf.subjectX500Principal.name,
                    issuer = leaf.issuerX500Principal.name,
                    notAfter = leaf.notAfter.time,
                    changed = pinned != null,
                ),
                e
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    /**
     * 主机名校验：系统信任的证书走标准校验；用户固定的自签证书常以 IP 访问、CN 不匹配，
     * 指纹已严格绑定端点，因此在指纹匹配时放行。
     */
    fun hostnameVerifier(): HostnameVerifier {
        val default = HttpsURLConnection.getDefaultHostnameVerifier()
        return HostnameVerifier { hostname: String, session: SSLSession ->
            if (default.verify(hostname, session)) return@HostnameVerifier true
            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
                ?: return@HostnameVerifier false
            val pinned = pinStore.fingerprintFor(endpointKey) ?: return@HostnameVerifier false
            pinned.equals(CertificatePinStore.sha256Of(leaf), ignoreCase = true)
        }
    }
}
