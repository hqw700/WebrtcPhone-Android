package com.webrtc_phone.android.rtc

import android.content.Context
import android.util.Log
import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.core.net.HttpClientFactory
import com.webrtc_phone.android.data.model.DeviceInfo
import com.webrtc_phone.android.data.model.Parsers
import com.webrtc_phone.android.rtc.channel.ClipboardProtocol
import com.webrtc_phone.android.rtc.channel.FileTransferClient
import com.webrtc_phone.android.rtc.channel.InputProtocol
import com.webrtc_phone.android.rtc.channel.SafeChannel
import com.webrtc_phone.android.rtc.channel.TerminalChannel
import com.webrtc_phone.android.rtc.signaling.CandidateSpec
import com.webrtc_phone.android.rtc.signaling.SignalingClient
import com.webrtc_phone.android.rtc.signaling.SignalingMessage
import com.webrtc_phone.android.rtc.signaling.SignalingProtocol
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

sealed interface ConnectionPhase {
    data object Signaling : ConnectionPhase
    data object WaitingOffer : ConnectionPhase
    data object Negotiating : ConnectionPhase
    data object Connected : ConnectionPhase
    /** ICE 瞬断，等待自愈 */
    data object Interrupted : ConnectionPhase
    data class Failed(val message: String, val retryable: Boolean, val authError: Boolean = false) : ConnectionPhase
    data object Closed : ConnectionPhase

    val terminal: Boolean get() = this is Failed || this == Closed
}

/**
 * 一次完整的 WebRTC 连接（信令 WS + PeerConnection + DataChannels）。
 *
 * 线程模型：所有状态变更与 PeerConnection/DataChannel 生命周期操作都在 [executor]
 * （单线程）上执行，WebRTC/OkHttp 回调一律转投过来，消除竞态与“死通道”访问。
 * 输入事件的 send 允许任意线程调用（SafeChannel 内部加锁保护）。
 */
class RtcSession(
    private val context: Context,
    private val engine: RtcEngine,
    private val httpFactory: HttpClientFactory,
    val target: StreamTarget,
    private val executor: ScheduledExecutorService,
) {
    private val _phase = MutableStateFlow<ConnectionPhase>(ConnectionPhase.Signaling)
    val phase: StateFlow<ConnectionPhase> = _phase.asStateFlow()

    private val _videoSize = MutableStateFlow(target.displayHint)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()

    private val _firstFrame = MutableStateFlow(false)
    val firstFrame: StateFlow<Boolean> = _firstFrame.asStateFlow()

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _remoteClipboard = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val remoteClipboard: SharedFlow<String> = _remoteClipboard.asSharedFlow()

    private val _files = MutableStateFlow<FileTransferClient?>(null)
    val files: StateFlow<FileTransferClient?> = _files.asStateFlow()

    private val _terminal = MutableStateFlow<TerminalChannel?>(null)
    val terminal: StateFlow<TerminalChannel?> = _terminal.asStateFlow()

    private val _inputReady = MutableStateFlow(false)
    val inputReady: StateFlow<Boolean> = _inputReady.asStateFlow()

    @Volatile private var inputChannel: SafeChannel? = null
    @Volatile private var clipboardChannel: SafeChannel? = null
    private var fileChannel: SafeChannel? = null
    private var adbChannel: SafeChannel? = null
    private val agentChannels = mutableListOf<SafeChannel>()

    val input = InputProtocol(
        sender = { text -> inputChannel?.send(text) ?: false },
        viewOnly = { target.viewOnly },
    )
    val clipboard = ClipboardProtocol(
        sender = { text -> clipboardChannel?.send(text) ?: false },
        enabled = { target.allowClipboard && !target.viewOnly },
    )

    private var signaling: SignalingClient? = null
    private var pc: PeerConnection? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var audioTrack: AudioTrack? = null
    @Volatile private var audioEnabled = true
    private val attachedTracks = HashSet<String>()
    private var statsTask: ScheduledFuture<*>? = null
    private var timeoutTask: ScheduledFuture<*>? = null
    private var interruptTask: ScheduledFuture<*>? = null
    private var firstFrameTask: ScheduledFuture<*>? = null
    private val statsCalc = StatsCalculator()
    @Volatile private var closed = false

    // 渲染目标可随 Activity 重建切换；为 null 时直接丢帧（后台保活）
    @Volatile private var renderer: VideoSink? = null

    private val frameSink = VideoSink { frame: VideoFrame ->
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        val cur = _videoSize.value
        if (w > 0 && h > 0 && (cur == null || cur.first != w || cur.second != h)) {
            _videoSize.value = w to h
        }
        if (!_firstFrame.value) _firstFrame.value = true
        renderer?.onFrame(frame)
    }

    fun setRenderer(sink: VideoSink?) {
        renderer = sink
    }

    fun setAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
        executor.execute { if (!closed) audioTrack?.setEnabled(enabled) }
    }

    // ---------- 生命周期 ----------

    fun start() = executor.execute {
        if (closed) return@execute
        _phase.value = ConnectionPhase.Signaling
        timeoutTask = executor.schedule({
            if (!closed && _phase.value != ConnectionPhase.Connected) {
                fail("连接超时，设备可能离线或网络受限", retryable = true)
            }
        }, CONNECT_TIMEOUT_S, TimeUnit.SECONDS)

        signaling = SignalingClient(httpFactory, target, object : SignalingClient.Listener {
            override fun onOpen() = post { _phase.value = ConnectionPhase.WaitingOffer }
            override fun onMessage(msg: SignalingMessage) = post { handleSignaling(msg) }
            override fun onClosed(error: ApiException?, httpCode: Int?) = post {
                when {
                    httpCode == 401 || httpCode == 403 ->
                        fail(if (target.isGuest) "卡密已失效或访问密码错误" else "登录已失效，请重新登录", retryable = false, auth = true)
                    error is ApiException.UntrustedCertificate -> fail(error.message ?: "证书不受信任", retryable = false)
                    _phase.value == ConnectionPhase.Connected -> {
                        // 媒体走 P2P，信令断开不影响已建立的画面；仅记录
                        Log.w(TAG, "signaling closed while connected: ${error?.message}")
                    }
                    else -> fail(error?.message ?: "信令连接已断开", retryable = true)
                }
            }
        }).also { it.connect() }
    }

    fun close() = executor.execute { teardown(ConnectionPhase.Closed) }

    private fun post(block: () -> Unit) {
        if (closed) return
        try {
            executor.execute { if (!closed) block() }
        } catch (_: Exception) {
        }
    }

    private fun fail(message: String, retryable: Boolean, auth: Boolean = false) {
        if (closed) return
        Log.w(TAG, "session failed: $message (retryable=$retryable)")
        teardown(ConnectionPhase.Failed(message, retryable, auth))
    }

    private fun teardown(finalPhase: ConnectionPhase) {
        if (closed) return
        closed = true
        statsTask?.cancel(false)
        timeoutTask?.cancel(false)
        interruptTask?.cancel(false)
        firstFrameTask?.cancel(false)
        signaling?.close()
        signaling = null
        renderer = null
        _inputReady.value = false
        // 先释放所有通道（native 仍存活，注销安全），再关闭 PeerConnection
        _files.value?.close()
        _files.value = null
        _terminal.value = null
        inputChannel = null
        clipboardChannel = null
        (agentChannels + listOfNotNull(fileChannel, adbChannel)).forEach { it.release() }
        agentChannels.clear()
        fileChannel = null
        adbChannel = null
        audioTrack = null
        pc?.let { p ->
            runCatching { p.close() }
            runCatching { p.dispose() }
        }
        pc = null
        _phase.value = finalPhase
    }

    // ---------- 信令 ----------

    private fun handleSignaling(msg: SignalingMessage) {
        when (msg) {
            is SignalingMessage.Config -> {
                createPeerConnection(msg)
                signaling?.send(SignalingProtocol.requestOffer(target.deviceId, ScrcpyOptions.build(target)))
                _phase.value = ConnectionPhase.WaitingOffer
            }
            is SignalingMessage.DeviceInfo -> {
                val info = Parsers.deviceInfo(msg.info)
                _deviceInfo.value = info
                info.primaryDisplay?.let { d -> if (_videoSize.value == null) _videoSize.value = d.width to d.height }
            }
            is SignalingMessage.Offer -> onRemoteOffer(msg.sdp)
            is SignalingMessage.Answer -> pc?.setRemoteDescription(
                sdpObserver("setRemoteAnswer") { post { onRemoteSet() } },
                SessionDescription(SessionDescription.Type.ANSWER, msg.sdp)
            )
            is SignalingMessage.Candidate -> addRemoteCandidate(msg.candidate)
            is SignalingMessage.Error -> {
                val text = if (msg.code == "SHARE_IN_USE") "该分享正被其他访客使用，单个分享同时仅允许一人在线" else msg.message
                fail(text, retryable = msg.code != "SHARE_IN_USE")
            }
            else -> Unit
        }
    }

    private fun createPeerConnection(cfg: SignalingMessage.Config) {
        if (pc != null) return
        val servers = cfg.iceServers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        val rtcConfig = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            keyType = PeerConnection.KeyType.ECDSA
        }
        val p = engine.factory().createPeerConnection(rtcConfig, pcObserver)
        if (p == null) {
            fail("创建连接失败", retryable = true)
            return
        }
        pc = p
        // file-channel / adb-channel 必须在协商前由本端创建：
        // 部分 libwebrtc 版本对协商后新建的通道不回调 OPEN
        val ordered = DataChannel.Init().apply { ordered = true }
        p.createDataChannel("file-channel", ordered)?.let { dc ->
            val ch = SafeChannel(dc, executor)
            fileChannel = ch
            _files.value = FileTransferClient(context, ch)
        }
        p.createDataChannel("adb-channel", DataChannel.Init().apply { this.ordered = true })?.let { dc ->
            val ch = SafeChannel(dc, executor)
            adbChannel = ch
            _terminal.value = TerminalChannel(ch)
        }
    }

    private fun onRemoteOffer(sdp: String) {
        val p = pc ?: return
        _phase.value = ConnectionPhase.Negotiating
        p.setRemoteDescription(sdpObserver("setRemoteOffer") {
            post {
                onRemoteSet()
                p.createAnswer(sdpObserver("createAnswer", onCreate = { answer ->
                    post {
                        p.setLocalDescription(sdpObserver("setLocalAnswer") {
                            post { signaling?.send(SignalingProtocol.answer(target.deviceId, answer.description)) }
                        }, answer)
                    }
                }), MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun onRemoteSet() {
        remoteDescriptionSet = true
        val p = pc ?: return
        pendingCandidates.forEach { p.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    private fun addRemoteCandidate(c: CandidateSpec) {
        val ice = IceCandidate(c.sdpMid ?: "0", c.sdpMLineIndex, c.candidate)
        if (!remoteDescriptionSet) pendingCandidates += ice else pc?.addIceCandidate(ice)
    }

    private fun sdpObserver(
        tag: String,
        onCreate: ((SessionDescription) -> Unit)? = null,
        onSet: (() -> Unit)? = null,
    ) = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {
            onCreate?.invoke(desc)
        }

        override fun onSetSuccess() {
            onSet?.invoke()
        }

        override fun onCreateFailure(error: String?) = post { fail("SDP 协商失败：$error", retryable = true) }
        override fun onSetFailure(error: String?) = post {
            Log.e(TAG, "$tag failed: $error")
            fail("SDP 协商失败：$error", retryable = true)
        }
    }

    // ---------- PeerConnection 回调（WebRTC 线程 → executor） ----------

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) = post {
            signaling?.send(
                SignalingProtocol.candidate(
                    target.deviceId, CandidateSpec(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                )
            )
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = post {
            Log.d(TAG, "ice: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> onConnected()
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    if (_phase.value == ConnectionPhase.Connected) {
                        _phase.value = ConnectionPhase.Interrupted
                        interruptTask?.cancel(false)
                        interruptTask = executor.schedule({
                            if (!closed && _phase.value == ConnectionPhase.Interrupted) {
                                fail("网络中断", retryable = true)
                            }
                        }, INTERRUPT_GRACE_S, TimeUnit.SECONDS)
                    }
                }
                PeerConnection.IceConnectionState.FAILED -> fail("网络穿透失败，请检查网络或配置 TURN 中继", retryable = true)
                else -> Unit
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            transceiver.receiver?.track()?.let { t -> post { attachTrack(t) } }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            receiver.track()?.let { t -> post { attachTrack(t) } }
        }

        override fun onDataChannel(dc: DataChannel) = post { onAgentChannel(dc) }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun onConnected() {
        interruptTask?.cancel(false)
        timeoutTask?.cancel(false)
        if (_phase.value != ConnectionPhase.Connected) {
            _phase.value = ConnectionPhase.Connected
        }
        // 连通但迟迟没有画面：通常是设备端采集失败（摄像头被占用/HAL 异常、编码器启动失败），
        // 给出明确提示而不是无限转圈
        if (firstFrameTask == null && !_firstFrame.value) {
            firstFrameTask = executor.schedule({
                if (!closed && !_firstFrame.value) {
                    fail(
                        if (target.isCamera) "云手机摄像头没有输出画面，摄像头可能被占用或不可用"
                        else "已连接但没有收到画面，设备编码器可能启动失败",
                        retryable = !target.isCamera,
                    )
                }
            }, FIRST_FRAME_TIMEOUT_S, TimeUnit.SECONDS)
        }
        if (statsTask == null) {
            statsTask = executor.scheduleWithFixedDelay({
                val p = pc ?: return@scheduleWithFixedDelay
                if (closed) return@scheduleWithFixedDelay
                p.getStats { report -> post { _stats.value = statsCalc.compute(report) } }
            }, 1, 1, TimeUnit.SECONDS)
        }
    }

    private fun attachTrack(track: MediaStreamTrack) {
        if (!attachedTracks.add(track.id())) return // onTrack/onAddTrack 会重复回调同一轨道
        when (track) {
            is VideoTrack -> track.addSink(frameSink)
            is AudioTrack -> {
                audioTrack = track
                track.setEnabled(audioEnabled)
            }
        }
    }

    private fun onAgentChannel(dc: DataChannel) {
        val ch = SafeChannel(dc, executor)
        agentChannels += ch
        when (ch.label) {
            "input-channel" -> {
                inputChannel = ch
                ch.observe(object : SafeChannel.Listener {
                    override fun onState(state: DataChannel.State) {
                        _inputReady.value = state == DataChannel.State.OPEN
                    }
                })
                _inputReady.value = ch.isOpen
            }
            "clipboard-channel" -> {
                clipboardChannel = ch
                ch.observe(object : SafeChannel.Listener {
                    override fun onMessage(data: ByteArray, binary: Boolean) {
                        if (binary) return
                        clipboard.parseIncoming(String(data, Charsets.UTF_8))?.let { _remoteClipboard.tryEmit(it) }
                    }
                })
            }
            else -> Log.d(TAG, "agent channel ${ch.label} ignored")
        }
    }

    companion object {
        private const val TAG = "RtcSession"
        private const val CONNECT_TIMEOUT_S = 25L
        private const val INTERRUPT_GRACE_S = 8L
        private const val FIRST_FRAME_TIMEOUT_S = 15L
    }
}
