package com.webrtc_phone.android.host

import android.os.Build
import android.os.Handler
import com.webrtc_phone.android.BuildConfig
import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.core.net.UntrustedCertificateInfo
import com.webrtc_phone.android.rtc.channel.Keys
import com.webrtc_phone.android.rtc.channel.SafeChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.UUID
import java.util.concurrent.Executor

/** WebSocket、PeerConnection 和手势状态统一在服务线程上更新。 */
internal class ProjectionSignaling(
    private val http: OkHttpClient,
    private val endpoint: ServerEndpoint,
    private val prefs: HostPreferences,
    private val handler: Handler,
    private val displaySize: () -> Pair<Int, Int>,
    private val status: (Boolean, String) -> Unit,
    private val publishRequest: (ProjectionStandbyService.Request?) -> Unit,
    private val stopCapture: () -> Unit,
    private val confirmCertificate: (UntrustedCertificateInfo) -> Unit,
    private val fatal: (String) -> Unit,
) {
    private data class Invitation(
        val clientId: Long,
        val ticket: String,
        val control: Boolean,
        val owner: String,
        val resumeKey: String,
    ) {
        val canResume get() = owner.isNotBlank() && resumeKey.isNotBlank()
    }

    private data class Capture(
        val ticket: String,
        val unattended: Boolean,
        val factory: PeerConnectionFactory,
        val video: VideoTrack,
        val fullDisplay: () -> Boolean,
    )
    private var invitation: Invitation? = null
    private var sharing: Invitation? = null
    private var peer: Peer? = null
    private var capture: Capture? = null
    private var unattendedTicket: String? = null
    val unattended get() = capture?.unattended == true
    private val trustedSignaling = endpoint.secure || endpoint.host in setOf("localhost", "127.0.0.1", "::1")
    private val expire = Runnable {
        invitation?.let { reject(it.ticket, "expired") }
    }
    private var socket: WebSocket? = null
    private var registered = false
    private var closed = false
    private val heartbeat = object : Runnable {
        override fun run() {
            send(JSONObject().put("type", "heartbeat"))
            handler.postDelayed(this, 20_000)
        }
    }
    private val retry = Runnable { connect() }
    private val registrationTimeout = Runnable {
        socket?.let { reconnect(it, "等待信令注册超时，正在重连") }
    }

    fun connect() {
        if (closed || socket != null) return
        status(false, "正在连接信令服务器")
        socket = http.newWebSocket(
            Request.Builder().url(endpoint.wsUrl("/register_agent")).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    handler.post { if (socket === webSocket) register() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    handler.post { if (socket === webSocket) receive(message) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    handler.post { reconnect(webSocket, "信令连接已断开，正在重连") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    handler.post {
                        if (socket !== webSocket) return@post
                        val untrusted = ApiException.from(t) as? ApiException.UntrustedCertificate
                        when {
                            untrusted != null -> {
                                disconnect()
                                if (untrusted.info.notAfter <= System.currentTimeMillis()) {
                                    fatal("信令服务器证书已过期，请联系管理员更新证书")
                                } else {
                                    confirmCertificate(untrusted.info)
                                }
                            }
                            response?.code in listOf(401, 403) -> fatal("信令服务器拒绝设备注册（HTTP ${response?.code}）")
                            t is javax.net.ssl.SSLException -> fatal("无法验证信令服务器证书，请检查服务器地址和证书")
                            else -> reconnect(webSocket, "信令连接失败，正在重连")
                        }
                    }
                }
            }
        )
        handler.postDelayed(registrationTimeout, 15_000)
    }

    private fun register() {
        val (width, height) = displaySize()
        val display = JSONObject().put("stream_id", "display_0")
            .put("x_res", width).put("y_res", height).put("is_touch", true)
        val info = JSONObject().put("model", Build.MODEL).put("brand", Build.MANUFACTURER)
            .put("android_version", Build.VERSION.RELEASE).put("sdk_int", Build.VERSION.SDK_INT)
            .put("camera_support", false).put("displays", JSONArray().put(display))
            .put("agent_version", BuildConfig.VERSION_NAME).put("app_version", BuildConfig.VERSION_NAME)
            .put("projection_on_demand", true)
        send(JSONObject().put("type", "agent_register").put("device_id", prefs.deviceId)
            .put("is_webrtc", true).put("device_info", info).put("ice_servers", prefs.iceServers))
    }

    private fun receive(message: JSONObject) {
        val id = message.optLong("client_id")
        when (message.optString("message_type").ifBlank { message.optString("type") }) {
            "agent_register_ok" -> {
                registered = true
                handler.removeCallbacks(registrationTimeout)
                handler.removeCallbacks(heartbeat)
                heartbeat.run()
                status(true, if (unattended) "亮屏无人值守 · 等待连接" else "在线 · 可请求投屏")
            }
            "error" -> fatal(message.optString("reason").ifBlank { message.optString("error", "信令服务器拒绝注册") })
            "client_disconnected" -> {
                if (invitation?.clientId == id) clearInvitation()
                if (sharing?.clientId == id) detachPeer("ended")
            }
            "command" -> {
                // 旧网页导航按钮走 command。仅映射这三个固定值到系统导航动作。
                val keycode = when (message.optString("command")) {
                    "input keyevent 3" -> Keys.HOME
                    "input keyevent 4" -> Keys.BACK
                    "input keyevent 187" -> Keys.APP_SWITCH
                    else -> return
                }
                peer?.takeIf { sharing?.clientId == id }?.navigation(keycode)
            }
            "forward" -> {
                if (!registered || id <= 0) return
                val payload = message.optJSONObject("payload") ?: return
                when (payload.optString("type")) {
                    "request-offer" -> {
                        val options = payload.optJSONObject("scrcpy_options")
                        if (options?.optString("video_source") == "camera" || options?.optBoolean("video", true) == false) return
                        val control = trustedSignaling && message.optBoolean("projection_input_allowed")
                        val owner = message.optString("projection_owner").takeIf { trustedSignaling }.orEmpty()
                        val resumeKey = payload.optString("projection_resume_key")
                            .takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }.orEmpty()
                        val allowUnattended = trustedSignaling && owner.isNotBlank() &&
                            message.optBoolean("projection_unattended_allowed")
                        if (unattended && !allowUnattended) {
                            sendStatus(id, "unattended_denied")
                            return
                        }
                        val active = sharing
                        if (active != null) {
                            if (active.canResume && active.owner == owner && active.resumeKey == resumeKey) {
                                attachPeer(active.copy(clientId = id, control = active.control && control))
                            } else if (active.clientId != id) {
                                sendStatus(id, "busy")
                            }
                            return
                        }
                        if (unattended) {
                            val source = checkNotNull(capture)
                            attachPeer(Invitation(id, source.ticket, control, owner, resumeKey))
                            return
                        }
                        if (unattendedTicket != null) {
                            sendStatus(id, "busy")
                            return
                        }
                        if (invitation != null) {
                            sendStatus(id, if (invitation?.clientId == id) "waiting" else "busy")
                            return
                        }
                        val ticket = UUID.randomUUID().toString()
                        invitation = Invitation(id, ticket, control, owner, resumeKey)
                        val requester = message.optString("projection_requester").take(120).ifBlank { "管理台用户" }
                        publishRequest(ProjectionStandbyService.Request(ticket, requester, control))
                        sendStatus(id, "waiting")
                        handler.postDelayed(expire, 60_000)
                    }
                    "projection-end" -> {
                        val active = sharing
                        if (active != null && (active.clientId == id || (
                                active.canResume && active.owner == message.optString("projection_owner") &&
                                    active.resumeKey == payload.optString("projection_resume_key")
                            ))) endShare("ended")
                        if (invitation?.clientId == id) clearInvitation()
                    }
                    "answer" -> peer?.takeIf { sharing?.clientId == id }?.answer(payload.optString("sdp"))
                    "ice-candidate" -> payload.optJSONObject("candidate")?.let { peer?.takeIf { sharing?.clientId == id }?.candidate(it) }
                }
            }
        }
    }

    private fun send(message: JSONObject) {
        socket?.send(message.toString())
    }

    private fun reconnect(connection: WebSocket, message: String) {
        if (socket !== connection || closed) return
        disconnect(keepUnattended = true)
        status(false, message)
        handler.postDelayed(retry, 3_000)
    }

    fun resetGestures() {
        peer?.gestures?.reset()
    }

    fun reserveUnattended(): String? {
        if (!trustedSignaling || !registered || capture != null || invitation != null || unattendedTicket != null) return null
        return UUID.randomUUID().toString().also { unattendedTicket = it }
    }

    fun isUnattendedRequest(ticket: String): Boolean = unattendedTicket == ticket

    fun isPending(ticket: String): Boolean =
        registered && (invitation?.ticket == ticket || isUnattendedRequest(ticket))

    fun reject(ticket: String, reason: String = "rejected") {
        if (isUnattendedRequest(ticket)) {
            unattendedTicket = null
            status(registered, if (reason == "failed") "无法开启亮屏无人值守" else "已取消亮屏无人值守")
            return
        }
        val request = invitation?.takeIf { it.ticket == ticket } ?: return
        clearInvitation()
        sendStatus(request.clientId, reason)
    }

    private fun clearInvitation() {
        invitation = null
        handler.removeCallbacks(expire)
        publishRequest(null)
    }

    fun startShare(ticket: String, factory: PeerConnectionFactory, video: VideoTrack, fullDisplay: () -> Boolean): Boolean {
        if (!isPending(ticket)) return false
        if (isUnattendedRequest(ticket)) {
            unattendedTicket = null
            capture = Capture(ticket, true, factory, video, fullDisplay)
            status(true, "亮屏无人值守 · 等待连接")
        } else {
            val request = invitation ?: return false
            clearInvitation()
            capture = Capture(ticket, false, factory, video, fullDisplay)
            attachPeer(request)
        }
        return capture?.ticket == ticket
    }

    private fun attachPeer(request: Invitation) {
        val source = capture ?: return
        closePeer()
        sharing = request
        val connection = Peer(request.clientId, request.control, source.factory, source.video, source.fullDisplay)
        peer = connection
        status(true, "正在共享屏幕")
        runCatching { connection.start() }.onFailure { connection.fail() }
    }

    private fun closePeer() {
        val old = peer
        peer = null
        old?.close()
    }

    private fun detachPeer(reason: String) {
        val request = sharing ?: return
        if (!unattended && !request.canResume) {
            endShare(reason)
            return
        }
        if (peer == null) return
        closePeer()
        if (reason == "failed") sendStatus(request.clientId, reason)
        if (unattended) sharing = null
        status(true, if (unattended) "亮屏无人值守 · 等待连接" else "连接已断开，等待管理台重连")
    }

    fun captureEnded(ticket: String, message: String) {
        if (capture?.ticket == ticket) endShare("ended", message)
        else reject(ticket, "failed")
    }

    private fun endShare(reason: String, message: String = "共享已结束") {
        if (capture == null) return
        val request = sharing
        sharing = null
        capture = null
        closePeer()
        if (registered) request?.let { sendStatus(it.clientId, reason) }
        status(registered, if (registered) "$message · 在线待命" else message)
        stopCapture()
    }

    private fun sendStatus(id: Long, state: String) {
        send(JSONObject().put("type", "forward").put("client_id", id)
            .put("payload", JSONObject().put("type", "projection_status").put("state", state)))
    }

    private fun disconnect(keepUnattended: Boolean = false) {
        val old = socket
        socket = null
        registered = false
        old?.cancel()
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(registrationTimeout)
        clearInvitation()
        unattendedTicket = null
        if (keepUnattended && unattended) {
            sharing = null
            closePeer()
        } else {
            endShare("ended")
        }
    }

    fun close() {
        closed = true
        handler.removeCallbacks(retry)
        disconnect()
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        return prefs.iceServers.split(',').mapNotNull { entry ->
            val url = entry.trim()
            if (url.isEmpty()) return@mapNotNull null
            val address = url.substringAfter('@')
            val credentials = url.substringBefore('@').substringAfter(':').split(':', limit = 2)
            if (url.startsWith("turn:") && '@' in url && credentials.size == 2) {
                PeerConnection.IceServer.builder("turn:$address")
                    .setUsername(credentials[0]).setPassword(credentials[1]).createIceServer()
            } else {
                PeerConnection.IceServer.builder(url).createIceServer()
            }
        }.ifEmpty {
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
    }

    private inner class Peer(
        private val id: Long,
        private val control: Boolean,
        private val factory: PeerConnectionFactory,
        private val video: VideoTrack,
        private val fullDisplay: () -> Boolean,
    ) : PeerConnection.Observer {
        val gestures = ProjectionGestures(displaySize)
        private var pc: PeerConnection? = null
        private var channel: SafeChannel? = null
        private val localCandidates = mutableListOf<IceCandidate>()
        private val remoteCandidates = mutableListOf<IceCandidate>()
        private var offerSent = false
        private var answerSet = false
        private var answerPending = false
        private val timeout = Runnable { fail() }

        fun start() {
            val config = PeerConnection.RTCConfiguration(iceServers()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val connection = checkNotNull(factory.createPeerConnection(config, this))
            pc = connection
            val transceiver = connection.addTransceiver(
                video, RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("projection")
                )
            )
            val codecs = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            if (codecs.any { it.mimeType.equals("video/H264", ignoreCase = true) }) {
                transceiver.setCodecPreferences(codecs.sortedBy { !it.mimeType.equals("video/H264", ignoreCase = true) })
            }
            if (control) {
                val raw = checkNotNull(connection.createDataChannel("input-channel", DataChannel.Init()))
                channel = SafeChannel(raw, Executor { handler.post(it) }).also {
                    it.observe(object : SafeChannel.Listener {
                        override fun onMessage(data: ByteArray, binary: Boolean) {
                            if (!binary && data.size <= 2048 && peer === this@Peer && fullDisplay()) {
                                gestures.receive(data.toString(Charsets.UTF_8))
                            }
                        }
                    })
                }
            }
            handler.postDelayed(timeout, 25_000)
            connection.createOffer(observer(onCreate = { offer ->
                connection.setLocalDescription(observer(onSet = {
                    forward(JSONObject().put("type", "offer").put("sdp", offer.description))
                    offerSent = true
                    localCandidates.forEach(::sendCandidate)
                    localCandidates.clear()
                }), offer)
            }), MediaConstraints())
        }

        fun navigation(keycode: Int) {
            if (control && fullDisplay() && channel?.isOpen == true) gestures.navigation(keycode)
        }

        fun answer(sdp: String) {
            if (!offerSent || answerPending || sdp.isBlank()) return
            answerPending = true
            pc?.setRemoteDescription(observer(onSet = {
                answerSet = true
                remoteCandidates.forEach { pc?.addIceCandidate(it) }
                remoteCandidates.clear()
            }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
        }

        fun candidate(value: JSONObject) {
            val sdp = value.optString("candidate")
            if (sdp.isBlank()) return
            val candidate = IceCandidate(
                value.optString("sdpMid").ifEmpty { value.optString("mid") },
                value.optInt("sdpMLineIndex"), sdp
            )
            if (answerSet) pc?.addIceCandidate(candidate)
            else if (remoteCandidates.size < 128) remoteCandidates.add(candidate)
        }

        private fun forward(payload: JSONObject) {
            send(JSONObject().put("type", "forward").put("client_id", id).put("payload", payload))
        }

        private fun sendCandidate(candidate: IceCandidate) {
            val value = JSONObject().put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex)
            forward(JSONObject().put("type", "ice-candidate").put("candidate", value))
        }

        private fun post(block: () -> Unit) {
            handler.post { if (peer === this) block() }
        }

        private fun observer(
            onCreate: (SessionDescription) -> Unit = {},
            onSet: () -> Unit = {},
        ) = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = post { onCreate(sdp) }
            override fun onSetSuccess() = post(onSet)
            override fun onCreateFailure(error: String) = post(::fail)
            override fun onSetFailure(error: String) = post(::fail)
        }

        fun fail() {
            if (peer === this) detachPeer("failed")
        }

        fun close() {
            handler.removeCallbacks(timeout)
            gestures.reset()
            channel?.closeAndRelease()
            pc?.dispose()
            pc = null
        }

        override fun onIceCandidate(candidate: IceCandidate) = post {
            if (offerSent) sendCandidate(candidate) else localCandidates.add(candidate)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = post {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED ->
                    handler.removeCallbacks(timeout)
                PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED -> fail()
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    handler.removeCallbacks(timeout)
                    handler.postDelayed(timeout, 10_000)
                }
                else -> Unit
            }
        }

        override fun onDataChannel(channel: DataChannel) {
            handler.post { channel.dispose() }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
    }
}
