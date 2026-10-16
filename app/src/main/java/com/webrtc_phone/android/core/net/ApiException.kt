package com.webrtc_phone.android.core.net

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 统一的接口错误类型，UI 只需按子类决定提示与处理方式 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(message: String, cause: Throwable? = null) : ApiException(message, cause)
    class Unauthorized(message: String = "登录已失效，请重新登录") : ApiException(message)
    class Forbidden(message: String = "无权限执行此操作") : ApiException(message)
    class NotFound(message: String = "资源不存在") : ApiException(message)
    class Http(val code: Int, message: String) : ApiException(message)
    class Business(val code: Int, message: String) : ApiException(message)
    class Parse(message: String = "服务器响应格式错误", cause: Throwable? = null) : ApiException(message, cause)
    class UntrustedCertificate(val info: UntrustedCertificateInfo) :
        ApiException(if (info.changed) "服务器证书已变更" else "服务器证书不受信任")

    companion object {
        /** 把底层异常归一化；沿 cause 链寻找证书固定异常 */
        fun from(t: Throwable): ApiException {
            if (t is ApiException) return t
            var c: Throwable? = t
            while (c != null) {
                if (c is UntrustedCertificateException) return UntrustedCertificate(c.info)
                c = c.cause
            }
            return when (t) {
                is UnknownHostException -> Network("无法解析服务器地址", t)
                is ConnectException -> Network("无法连接服务器，请检查地址与网络", t)
                is SocketTimeoutException -> Network("连接服务器超时", t)
                is SSLException -> Network("安全连接失败：${t.message ?: "TLS 握手错误"}", t)
                is IOException -> Network("网络错误：${t.message ?: t.javaClass.simpleName}", t)
                else -> Network(t.message ?: t.javaClass.simpleName, t)
            }
        }
    }
}
