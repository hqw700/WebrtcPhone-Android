package com.webrtc_phone.android.host

import android.os.Build
import android.os.Handler
import com.webrtc_phone.android.BuildConfig
import com.webrtc_phone.android.core.net.ServerEndpoint
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
import java.util.concurrent.Executor

/** WebSocket、PeerConnection 和手势状态统一在服务线程上更新。 */
internal class ProjectionSignaling(
    private val http: OkHttpClient,
    private val endpoint: ServerEndpoint,
    private val prefs: HostPreferences,
    private val factory: PeerConnectionFactory,
    private val video: VideoTrack,
    private val handler: Handler,
    private val displaySize: () -> Pair<Int, Int>,
    private val fullDisplay: () -> Boolean,
    private val status: (Boolean, String) -> Unit,
    private val fatal: (String) -> Unit,
) {
    private val peers = mutableMapOf<Long, Peer>()
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
                        when {
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
            .put("agent_version", BuildConfig.VERSION_NAME)
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
                status(true, "正在共享屏幕")
            }
            "error" -> fatal(message.optString("reason").ifBlank { message.optString("error", "信令服务器拒绝注册") })
            "client_disconnected" -> peers.remove(id)?.close()
            "command" -> {
                // 旧网页导航按钮走 command。仅映射这三个固定值到系统导航动作。
                val keycode = when (message.optString("command")) {
                    "input keyevent 3" -> Keys.HOME
                    "input keyevent 4" -> Keys.BACK
                    "input keyevent 187" -> Keys.APP_SWITCH
                    else -> return
                }
                peers[id]?.navigation(keycode)
            }
            "forward" -> {
                if (!registered || id <= 0) return
                val payload = message.optJSONObject("payload") ?: return
                when (payload.optString("type")) {
                    "request-offer" -> {
                        val options = payload.optJSONObject("scrcpy_options")
                        if (options?.optString("video_source") == "camera" || options?.optBoolean("video", true) == false) return
                        peers.remove(id)?.close()
                        if (peers.size >= 4) return
                        val control = message.optBoolean("projection_input_allowed") &&
                            (endpoint.secure || endpoint.host in setOf("localhost", "127.0.0.1", "::1"))
                        val peer = Peer(id, control)
                        peers[id] = peer
                        runCatching { peer.start() }.onFailure { peer.fail() }
                    }
                    "answer" -> peers[id]?.answer(payload.optString("sdp"))
                    "ice-candidate" -> payload.optJSONObject("candidate")?.let { peers[id]?.candidate(it) }
                }
            }
        }
    }

    private fun send(message: JSONObject) {
        socket?.send(message.toString())
    }

    private fun reconnect(connection: WebSocket, message: String) {
        if (socket !== connection || closed) return
        disconnect()
        status(false, message)
        handler.postDelayed(retry, 3_000)
    }

    fun resetGestures() {
        peers.values.forEach { it.gestures.reset() }
    }

    private fun disconnect() {
        val old = socket
        socket = null
        registered = false
        old?.cancel()
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(registrationTimeout)
        val oldPeers = peers.values.toList()
        peers.clear()
        oldPeers.forEach { it.close() }
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

    private inner class Peer(private val id: Long, private val control: Boolean) : PeerConnection.Observer {
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
                            if (!binary && data.size <= 2048 && peers[id] === this@Peer && fullDisplay()) {
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
            handler.post { if (peers[id] === this) block() }
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
            if (peers[id] !== this) return
            peers.remove(id)
            close()
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
