package com.webrtc_phone.android.host

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.webrtc_phone.android.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.security.auth.x500.X500Principal

/**
 * 免 Root / 免 ADB 系统录屏模式的本地独立 HTTPS/WSS 轻信令服务端。
 * 与 cloudphone-agent 的 -standalone 协议保持一致，同时支持 WebrtcPhone 单机直连与浏览器直接访问。
 */
internal class StandaloneProjectionServer(
    private val context: Context,
    private val port: Int,
    private val password: String,
    private val prefs: HostPreferences,
    private val displaySize: () -> Pair<Int, Int>,
    private val onSignalingMessage: (JSONObject) -> Unit,
    private val onClientDisconnected: (Long) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val nextClientId = AtomicLong(1)
    private val clients = ConcurrentHashMap<Long, WsClient>()
    private val tokens = ConcurrentHashMap<String, Long>()
    private val random = SecureRandom()
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "StandaloneProjSrv").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var fingerprintSha256: String = ""
        private set

    private val uiHtml: ByteArray by lazy {
        runCatching {
            context.assets.open("standalone_ui.html").use { it.readBytes() }
        }.getOrElse {
            "<!doctype html><html><body><h3>WebrtcPhone Standalone</h3></body></html>".toByteArray(Charsets.UTF_8)
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val (sslContext, fp) = loadOrCreateTlsContext()
        fingerprintSha256 = fp
        val srv = (sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        serverSocket = srv
        Log.i(TAG, "Standalone projection HTTPS/WSS server listening on :$port (SHA-256: $fp, auth=${password.isNotEmpty()})")
        executor.execute { acceptLoop(srv) }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.values.forEach { it.closeQuietly() }
        clients.clear()
        executor.shutdownNow()
    }

    fun sendFromProjection(msg: JSONObject) {
        val type = msg.optString("type")
        if (type != "forward") return
        val clientId = msg.optLong("client_id")
        val payload = msg.optJSONObject("payload") ?: return
        val client = clients[clientId] ?: return
        val envelope = JSONObject()
            .put("message_type", "device_msg")
            .put("device_id", prefs.deviceId)
            .put("payload", payload)
        client.sendText(envelope.toString())
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (running.get()) {
            val socket = try {
                srv.accept()
            } catch (_: IOException) {
                if (!running.get()) break
                continue
            }
            executor.execute { handleConnection(socket) }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = readLineCRLF(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeHttp(output, 400, "Bad Request", "text/plain", "Bad Request".toByteArray())
                return
            }
            val method = parts[0].uppercase()
            val rawTarget = parts[1]
            val qIdx = rawTarget.indexOf('?')
            val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
            val query = if (qIdx >= 0) rawTarget.substring(qIdx + 1) else ""

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLineCRLF(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }

            if (method == "OPTIONS") {
                writeHttp(output, 200, "OK", "text/plain", ByteArray(0), cors = true)
                return
            }

            val isWsUpgrade = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true &&
                !headers["sec-websocket-key"].isNullOrBlank()
            if (path == "/connect_client" && isWsUpgrade) {
                if (!isAuthorized(headers, query)) {
                    writeJson(output, 401, JSONObject().put("error", "Unauthorized"))
                    return
                }
                handleWebSocket(socket, input, output, headers["sec-websocket-key"]!!)
                return
            }

            when (path) {
                "/", "/index.html" -> {
                    if (method != "GET" && method != "HEAD") {
                        writeHttp(output, 405, "Method Not Allowed", "text/plain", ByteArray(0))
                    } else {
                        writeHttp(
                            output, 200, "OK", "text/html; charset=utf-8",
                            if (method == "HEAD") ByteArray(0) else uiHtml,
                            contentLengthOverride = uiHtml.size
                        )
                    }
                }
                "/api/standalone/ping" -> {
                    val body = JSONObject()
                        .put("standalone", true)
                        .put("tls", true)
                        .put("auth_required", password.isNotEmpty())
                        .put("device_id", prefs.deviceId)
                        .put("mode", "projection")
                    writeJson(output, 200, body)
                }
                "/api/login" -> {
                    if (method != "POST") {
                        writeJson(output, 405, JSONObject().put("error", "Method not allowed"))
                        return
                    }
                    val contentLen = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 16_384) ?: 0
                    val bodyBytes = readExact(input, contentLen)
                    val reqJson = runCatching { JSONObject(String(bodyBytes, Charsets.UTF_8)) }.getOrNull() ?: JSONObject()
                    val provided = reqJson.optString("password", "")
                    if (password.isNotEmpty() && !constantTimeEquals(password, provided)) {
                        writeJson(output, 401, JSONObject().put("error", "密码错误"))
                        return
                    }
                    val tok = issueToken()
                    writeJson(output, 200, JSONObject().put("token", tok).put("role", "admin").put("username", "direct"))
                }
                "/api/logout" -> {
                    extractToken(headers["authorization"], query)?.let { tokens.remove(it) }
                    writeJson(output, 200, JSONObject().put("ok", true))
                }
                "/api/me" -> {
                    if (!isAuthorized(headers, query)) {
                        writeJson(output, 401, JSONObject().put("error", "Unauthorized"))
                        return
                    }
                    writeJson(output, 200, JSONObject().put("username", "direct").put("role", "admin").put("standalone", true))
                }
                "/devices" -> {
                    if (!isAuthorized(headers, query)) {
                        writeJson(output, 401, JSONObject().put("error", "Unauthorized"))
                        return
                    }
                    val dev = JSONObject()
                        .put("device_id", prefs.deviceId)
                        .put("online", true)
                        .put("screen_state", "on")
                        .put("client_count", clients.size)
                        .put("device_info", buildDeviceInfo())
                    writeJsonArray(output, 200, JSONArray().put(dev))
                }
                "/api/tags" -> {
                    if (!isAuthorized(headers, query)) {
                        writeJson(output, 401, JSONObject().put("error", "Unauthorized"))
                        return
                    }
                    writeJson(output, 200, JSONObject().put("tags", JSONArray()).put("deviceTags", JSONObject()))
                }
                "/api/quick_texts" -> {
                    if (!isAuthorized(headers, query)) {
                        writeJson(output, 401, JSONObject().put("error", "Unauthorized"))
                        return
                    }
                    writeJsonArray(output, 200, JSONArray())
                }
                else -> writeHttp(output, 404, "Not Found", "text/plain", "Not Found".toByteArray())
            }
        } catch (_: Exception) {
            // 连接关闭或握手失败
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleWebSocket(socket: Socket, input: InputStream, output: OutputStream, wsKey: String) {
        val accept = computeWebSocketAccept(wsKey)
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()

        socket.soTimeout = 0
        val clientId = nextClientId.getAndIncrement()
        val client = WsClient(clientId, socket, output)
        clients[clientId] = client

        try {
            while (running.get() && !socket.isClosed) {
                val frame = readWsFrame(input) ?: break
                when (frame.opcode) {
                    0x8 -> { // Close
                        client.sendControl(0x8, ByteArray(0))
                        break
                    }
                    0x9 -> client.sendControl(0xA, frame.payload) // Ping -> Pong
                    0xA -> Unit // Pong
                    0x1 -> { // Text
                        val text = String(frame.payload, Charsets.UTF_8)
                        val msg = runCatching { JSONObject(text) }.getOrNull() ?: continue
                        val mType = msg.optString("message_type").ifBlank { msg.optString("type") }
                        when (mType) {
                            "ping" -> client.sendText(JSONObject().put("type", "pong").toString())
                            "connect" -> {
                                client.sendText(
                                    JSONObject()
                                        .put("message_type", "config")
                                        .put("ice_servers", parseIceServersJson(prefs.iceServers))
                                        .toString()
                                )
                                client.sendText(
                                    JSONObject()
                                        .put("message_type", "device_info")
                                        .put("device_info", buildDeviceInfo())
                                        .toString()
                                )
                            }
                            "forward", "command" -> {
                                msg.put("client_id", clientId)
                                onSignalingMessage(msg)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 客户端断开
        } finally {
            clients.remove(clientId)
            client.closeQuietly()
            onClientDisconnected(clientId)
        }
    }

    private fun buildDeviceInfo(): JSONObject {
        val (w, h) = displaySize()
        val display = JSONObject()
            .put("stream_id", "display_0")
            .put("x_res", w)
            .put("y_res", h)
            .put("is_touch", true)
        return JSONObject()
            .put("model", Build.MODEL)
            .put("brand", Build.MANUFACTURER)
            .put("android_version", Build.VERSION.RELEASE)
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("camera_support", false)
            .put("projection_on_demand", true)
            .put("displays", JSONArray().put(display))
            .put("agent_version", BuildConfig.VERSION_NAME)
            .put("app_version", BuildConfig.VERSION_NAME)
    }

    private fun issueToken(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val tok = bytes.joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        tokens.entries.removeIf { it.value <= now }
        if (tokens.size >= MAX_TOKENS) tokens.clear()
        tokens[tok] = now + TOKEN_TTL_MS
        return tok
    }

    private fun isAuthorized(headers: Map<String, String>, query: String): Boolean {
        if (password.isEmpty()) return true
        val tok = extractToken(headers["authorization"], query) ?: return false
        val exp = tokens[tok] ?: return false
        return System.currentTimeMillis() < exp
    }

    private fun loadOrCreateTlsContext(): Pair<SSLContext, String> {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getCertificate(TLS_ALIAS) as? X509Certificate
        val now = System.currentTimeMillis()
        val needGenerate = existing == null ||
            existing.notAfter.time <= now + 7L * 24 * 3600 * 1000L
        if (needGenerate) {
            if (ks.containsAlias(TLS_ALIAS)) runCatching { ks.deleteEntry(TLS_ALIAS) }
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(TLS_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512,
                    KeyProperties.DIGEST_NONE
                )
                .setCertificateSubject(X500Principal("CN=CloudPhone Standalone Host, O=CloudPhone"))
                .setCertificateSerialNumber(BigInteger(128, random).abs().max(BigInteger.ONE))
                .setCertificateNotBefore(Date(now - 3_600_000L))
                .setCertificateNotAfter(Date(now + 10L * 365 * 24 * 3600 * 1000L))
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
        val cert = ks.getCertificate(TLS_ALIAS) as X509Certificate
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val fp = digest.joinToString(":") { "%02X".format(it) }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, null)
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, null)
        }
        return ssl to fp
    }

    private class WsClient(
        val id: Long,
        private val socket: Socket,
        private val output: OutputStream,
    ) {
        private val writeLock = Any()

        fun sendText(text: String) {
            writeFrame(0x1, text.toByteArray(Charsets.UTF_8))
        }

        fun sendControl(opcode: Int, payload: ByteArray) {
            writeFrame(opcode, payload)
        }

        private fun writeFrame(opcode: Int, payload: ByteArray) {
            synchronized(writeLock) {
                runCatching {
                    val header = ByteArrayOutputStream(10)
                    header.write(0x80 or (opcode and 0x0F))
                    val len = payload.size
                    when {
                        len <= 125 -> header.write(len)
                        len <= 65535 -> {
                            header.write(126)
                            header.write((len shr 8) and 0xFF)
                            header.write(len and 0xFF)
                        }
                        else -> {
                            header.write(127)
                            val l64 = len.toLong()
                            for (i in 7 downTo 0) header.write(((l64 ushr (i * 8)) and 0xFF).toInt())
                        }
                    }
                    output.write(header.toByteArray())
                    output.write(payload)
                    output.flush()
                }
            }
        }

        fun closeQuietly() {
            runCatching { socket.close() }
        }
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray)

    companion object {
        private const val TAG = "StandaloneProjSrv"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TLS_ALIAS = "cloudphone_standalone_projection_tls"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val TOKEN_TTL_MS = 7L * 24 * 3600 * 1000L
        private const val MAX_TOKENS = 128
        private const val MAX_WS_PAYLOAD = 256 * 1024

        internal fun computeWebSocketAccept(key: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
                .digest((key.trim() + WS_GUID).toByteArray(Charsets.US_ASCII))
            return encodeBase64(sha1)
        }

        private fun encodeBase64(bytes: ByteArray): String {
            val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val sb = StringBuilder((bytes.size + 2) / 3 * 4)
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i++].toInt() and 0xFF
                val hasB1 = i < bytes.size
                val b1 = if (hasB1) bytes[i++].toInt() and 0xFF else 0
                val hasB2 = i < bytes.size
                val b2 = if (hasB2) bytes[i++].toInt() and 0xFF else 0
                sb.append(table[b0 ushr 2])
                sb.append(table[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                sb.append(if (hasB1) table[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
                sb.append(if (hasB2) table[b2 and 0x3F] else '=')
            }
            return sb.toString()
        }

        internal fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

        internal fun extractToken(authHeader: String?, query: String): String? {
            val auth = authHeader?.trim().orEmpty()
            if (auth.length > 7 && auth.substring(0, 7).equals("Bearer ", ignoreCase = true)) {
                return auth.substring(7).trim().takeIf { it.isNotEmpty() }
            }
            if (query.isEmpty()) return null
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0 && pair.substring(0, eq) == "token") {
                    return runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }
                        .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
            return null
        }

        internal fun parseIceServersJson(raw: String): JSONArray {
            val arr = JSONArray()
            raw.split(',').forEach { entry ->
                val url = entry.trim()
                if (url.isEmpty()) return@forEach
                val item = JSONObject()
                val at = url.indexOf('@')
                if ((url.startsWith("turn:") || url.startsWith("turns:")) && at > 0) {
                    val scheme = url.substringBefore(':')
                    val creds = url.substring(scheme.length + 1, at).split(':', limit = 2)
                    val host = url.substring(at + 1)
                    item.put("urls", JSONArray().put("$scheme:$host"))
                    if (creds.size == 2) {
                        item.put("username", creds[0])
                        item.put("credential", creds[1])
                    }
                } else {
                    item.put("urls", JSONArray().put(url))
                }
                arr.put(item)
            }
            if (arr.length() == 0) {
                arr.put(JSONObject().put("urls", JSONArray().put("stun:stun.l.google.com:19302")))
            }
            return arr
        }

        private fun readLineCRLF(input: InputStream): String? {
            val buf = ByteArrayOutputStream(128)
            var prev = -1
            var count = 0
            while (count < 8192) {
                val b = input.read()
                if (b == -1) return if (buf.size() == 0) null else String(buf.toByteArray(), Charsets.UTF_8)
                count++
                if (b == '\n'.code) {
                    val raw = buf.toByteArray()
                    val end = if (prev == '\r'.code && raw.isNotEmpty()) raw.size - 1 else raw.size
                    return String(raw, 0, end, Charsets.UTF_8)
                }
                buf.write(b)
                prev = b
            }
            return null
        }

        private fun readExact(input: InputStream, length: Int): ByteArray {
            val out = ByteArray(length)
            var off = 0
            while (off < length) {
                val n = input.read(out, off, length - off)
                if (n <= 0) throw EOFException()
                off += n
            }
            return out
        }

        private fun readWsFrame(input: InputStream): WsFrame? {
            val b0 = input.read()
            if (b0 == -1) return null
            val b1 = input.read()
            if (b1 == -1) return null
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                val ext = readExact(input, 2)
                len = ((ext[0].toInt() and 0xFF) shl 8 or (ext[1].toInt() and 0xFF)).toLong()
            } else if (len == 127L) {
                val ext = readExact(input, 8)
                len = 0L
                for (i in 0..7) len = (len shl 8) or (ext[i].toLong() and 0xFF)
            }
            if (len < 0 || len > MAX_WS_PAYLOAD) throw IOException("WebSocket frame too large: $len")
            val mask = if (masked) readExact(input, 4) else null
            val payload = readExact(input, len.toInt())
            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }
            return WsFrame(opcode, payload)
        }

        private fun writeJson(output: OutputStream, code: Int, json: JSONObject) {
            val reason = if (code == 200) "OK" else if (code == 401) "Unauthorized" else "Error"
            writeHttp(output, code, reason, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8), cors = true)
        }

        private fun writeJsonArray(output: OutputStream, code: Int, arr: JSONArray) {
            writeHttp(output, code, "OK", "application/json; charset=utf-8", arr.toString().toByteArray(Charsets.UTF_8), cors = true)
        }

        private fun writeHttp(
            output: OutputStream,
            code: Int,
            reason: String,
            contentType: String,
            body: ByteArray,
            cors: Boolean = false,
            contentLengthOverride: Int = body.size,
        ) {
            val sb = StringBuilder()
                .append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(contentLengthOverride).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n")
            if (cors) {
                sb.append("Access-Control-Allow-Origin: *\r\n")
                    .append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
                    .append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            }
            sb.append("\r\n")
            output.write(sb.toString().toByteArray(Charsets.US_ASCII))
            if (body.isNotEmpty()) output.write(body)
            output.flush()
        }
    }
}
