package com.webrtc_phone.android.data.api

import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.core.net.HttpClientFactory
import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.core.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 面向单个服务器的底层 HTTP 客户端：鉴权头、错误归一化、401 回调。
 * 业务层只拿到字符串/JSON 或 [ApiException]。
 */
class ApiClient(
    private val httpFactory: HttpClientFactory,
    val endpoint: ServerEndpoint,
    private val tokenProvider: () -> String?,
    private val onUnauthorized: () -> Unit = {},
) {

    val http get() = httpFactory.forEndpoint(endpoint)

    suspend fun get(path: String, auth: Boolean = true): String =
        execute(Request.Builder().url(endpoint.url(path)).get(), auth)

    suspend fun post(path: String, body: Any, auth: Boolean = true): String {
        val json = when (body) {
            is JSONObject, is JSONArray -> body.toString()
            is String -> body
            else -> error("unsupported body type ${body::class}")
        }
        return execute(
            Request.Builder().url(endpoint.url(path)).post(json.toRequestBody(JSON)),
            auth
        )
    }

    suspend fun delete(path: String): String =
        execute(Request.Builder().url(endpoint.url(path)).delete(), true)

    /**
     * 原始字节流 POST（服务器中转上传用）。body 已是构造好的 RequestBody（可带进度回调）。
     * 复用统一的鉴权头与错误归一化。
     */
    suspend fun postRaw(path: String, body: okhttp3.RequestBody): String =
        execute(Request.Builder().url(endpoint.url(path)).post(body), auth = true)

    /** `{code,msg,data}` 信封：code!=0 抛 Business，返回整个对象供调用方取 data */
    suspend fun postEnvelope(path: String, body: JSONObject, auth: Boolean = true): JSONObject =
        envelope(post(path, body, auth))

    suspend fun getEnvelope(path: String): JSONObject = envelope(get(path))

    private suspend fun execute(builder: Request.Builder, auth: Boolean): String {
        if (auth) {
            val token = tokenProvider() ?: throw ApiException.Unauthorized()
            builder.header("Authorization", "Bearer $token")
        }
        val response = try {
            http.newCall(builder.build()).await()
        } catch (e: Exception) {
            throw ApiException.from(e)
        }
        return withContext(Dispatchers.IO) {
            response.use { r ->
                val text = try {
                    r.body?.string().orEmpty()
                } catch (e: Exception) {
                    throw ApiException.from(e)
                }
                when {
                    r.isSuccessful -> text
                    r.code == 401 -> {
                        if (auth) onUnauthorized()
                        throw ApiException.Unauthorized(errorText(text, "登录已失效，请重新登录"))
                    }
                    r.code == 403 -> throw ApiException.Forbidden(errorText(text, "无权限执行此操作"))
                    r.code == 404 -> throw ApiException.NotFound(errorText(text, "资源不存在"))
                    else -> throw ApiException.Http(r.code, errorText(text, "请求失败 (HTTP ${r.code})"))
                }
            }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** 服务端错误体可能是纯文本（http.Error）也可能是 {error}/{msg} JSON */
        fun errorText(body: String, fallback: String): String {
            val t = body.trim()
            if (t.isEmpty()) return fallback
            if (t.startsWith("{")) {
                runCatching {
                    val o = JSONObject(t)
                    val m = o.optString("error").ifBlank { o.optString("msg") }
                    if (m.isNotBlank()) return translate(m)
                }
            }
            return translate(t.take(200))
        }

        /** 常见英文错误转中文，未知的原样返回 */
        private fun translate(msg: String): String = when (msg.trim()) {
            "Invalid username or password" -> "用户名或密码错误"
            "Username and password are required" -> "请输入用户名和密码"
            "Username already exists" -> "用户名已存在"
            "Unauthorized" -> "登录已失效，请重新登录"
            "Forbidden" -> "无权限执行此操作"
            "Share token not found" -> "分享不存在或已失效"
            else -> msg.trim()
        }

        fun envelope(body: String): JSONObject {
            val o = try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw ApiException.Parse(cause = e)
            }
            val code = o.optInt("code", 0)
            if (code != 0) {
                throw ApiException.Business(code, o.optString("msg").ifBlank { "操作失败 ($code)" })
            }
            return o
        }

        inline fun <T> parse(block: () -> T): T = try {
            block()
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException.Parse(cause = e)
        }
    }
}
