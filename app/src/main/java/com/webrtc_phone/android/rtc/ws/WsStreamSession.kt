package com.webrtc_phone.android.rtc.ws

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.webrtc_phone.android.core.net.HttpClientFactory
import com.webrtc_phone.android.rtc.ConnectionPhase
import com.webrtc_phone.android.rtc.StreamAuth
import com.webrtc_phone.android.rtc.StreamStats
import com.webrtc_phone.android.rtc.StreamTarget
import com.webrtc_phone.android.rtc.channel.InputProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * WS 模式投屏会话：通过 /connect_client WebSocket 收 H.264 裸流（PREV 帧），MediaCodec 解码到 Surface。
 * WebRTC P2P 的免穿透替代（100% TCP）。协议对齐 web-app。
 *
 * 线程模型：WS 回调在 OkHttp 线程；解码在单线程 [decodeExecutor]；状态 StateFlow 可任意线程读。
 * 仅支持视频 + 输入（touch/key/text/scroll 经 group_control_event）；无音频/文件/终端（与 web WS 模式一致）。
 */
class WsStreamSession(
    val target: StreamTarget,
    private val httpFactory: HttpClientFactory,
) {
    private val _phase = MutableStateFlow<ConnectionPhase>(ConnectionPhase.Signaling)
    val phase: StateFlow<ConnectionPhase> = _phase.asStateFlow()

    private val _videoSize = MutableStateFlow(target.displayHint)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()

    private val _firstFrame = MutableStateFlow(false)
    val firstFrame: StateFlow<Boolean> = _firstFrame.asStateFlow()

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    /** 输入：所有事件包进 group_control_event 下发同一条 WS（而非 WebRTC DataChannel） */
    val input = InputProtocol(
        sender = { eventJson -> sendControl(eventJson) },
        viewOnly = { target.viewOnly },
    )

    @Volatile private var ws: WebSocket? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var configured = false
    @Volatile private var gotKeyFrame = false
    @Volatile private var closed = false

    private val decodeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ws-decode") }
    private val statsExecutor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ws-stats") }
    private var statsTask: ScheduledFuture<*>? = null

    // 统计
    private val frameCount = AtomicLong(0)
    private val byteCount = AtomicLong(0)
    @Volatile private var lastStatsMs = System.currentTimeMillis()

    fun setSurface(s: Surface?) {
        surface = s
        // Surface 就绪后若已收到帧会在下一个关键帧重配解码器
        if (s == null) releaseCodec()
    }

    fun start() {
        if (closed) return
        _phase.value = ConnectionPhase.Signaling
        val token = (target.auth as? StreamAuth.User)?.token.orEmpty()
        val url = target.endpoint.wsUrl("/connect_client") + "?token=$token"
        val req = Request.Builder().url(url).build()
        ws = httpFactory.webSocketFor(target.endpoint).newWebSocket(req, listener)
        statsTask = statsExecutor.scheduleWithFixedDelay({ tickStats() }, 1, 1, TimeUnit.SECONDS)
    }

    fun close() {
        if (closed) return
        closed = true
        statsTask?.cancel(false)
        runCatching { ws?.send(WsProtocol.stopPreview(target.deviceId)) }
        runCatching { ws?.close(1000, "bye") }
        ws = null
        releaseCodec()
        decodeExecutor.shutdownNow()
        statsExecutor.shutdownNow()
        if (!_phase.value.terminal) _phase.value = ConnectionPhase.Closed
    }

    // 兼容 SessionManager 的音频开关（WS 模式无音频，空实现）
    fun setAudioEnabled(enabled: Boolean) {}

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed) return
            _phase.value = ConnectionPhase.WaitingOffer
            // 请求推流
            val s = target.settings
            val bps = (s.bitrateMbps.coerceAtLeast(1)) * 1_000_000
            webSocket.send(
                WsProtocol.startPreview(
                    deviceId = target.deviceId,
                    fps = s.maxFps,
                    maxSize = s.maxSize,
                    bitrateBps = bps,
                    stayAwake = true,
                )
            )
            _phase.value = ConnectionPhase.Negotiating
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // WS 模式下文本多为设备列表/状态，这里不需要处理；忽略以保持简单
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (closed) return
            val data = bytes.toByteArray()
            val frame = WsProtocol.parsePrev(data) ?: return
            if (frame.deviceId.isNotEmpty() && frame.deviceId != target.deviceId) return
            byteCount.addAndGet(frame.nalu.size.toLong())
            decodeExecutor.execute { feedDecoder(frame.nalu, frame.isKey) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closed) return
            Log.w(TAG, "ws failure: ${t.message} code=${response?.code}")
            val auth = response?.code == 401
            _phase.value = ConnectionPhase.Failed(
                message = if (auth) "登录已失效" else "连接失败：${t.message ?: "网络错误"}",
                retryable = !auth, authError = auth,
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (closed) return
            _phase.value = ConnectionPhase.Failed("连接已断开", retryable = true)
        }
    }

    private fun sendControl(eventJson: String): Boolean {
        val socket = ws ?: return false
        return socket.send(WsProtocol.groupControlEvent(target.deviceId, JSONObject(eventJson)))
    }

    // ---------- 解码（decodeExecutor 单线程） ----------

    private fun feedDecoder(nalu: ByteArray, isKey: Boolean) {
        if (closed) return
        if (!gotKeyFrame) {
            if (!isKey) return
            gotKeyFrame = true
        }
        val c = ensureCodec(nalu, isKey) ?: return
        try {
            val inIdx = c.dequeueInputBuffer(10_000L)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx) ?: return
                buf.clear()
                buf.put(nalu)
                val flags = if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                c.queueInputBuffer(inIdx, 0, nalu.size, System.nanoTime() / 1000, flags)
            }
            drainDecoder(c)
        } catch (e: Exception) {
            Log.w(TAG, "decode error: ${e.message}")
            // 解码器异常：重置，等待下一个关键帧
            releaseCodec()
            gotKeyFrame = false
        }
    }

    private fun drainDecoder(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var idx = c.dequeueOutputBuffer(info, 0L)
        while (idx >= 0) {
            c.releaseOutputBuffer(idx, true) // render to surface
            if (!_firstFrame.value) {
                _firstFrame.value = true
                _phase.value = ConnectionPhase.Connected
            }
            frameCount.incrementAndGet()
            idx = c.dequeueOutputBuffer(info, 0L)
        }
        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val f = c.outputFormat
            val w = f.getInteger(MediaFormat.KEY_WIDTH)
            val h = f.getInteger(MediaFormat.KEY_HEIGHT)
            if (w > 0 && h > 0) _videoSize.value = w to h
        }
    }

    /** 首个关键帧（含 SPS/PPS）到达时用其尺寸提示创建解码器；Surface 未就绪则等待 */
    private fun ensureCodec(nalu: ByteArray, isKey: Boolean): MediaCodec? {
        codec?.let { return it }
        val s = surface ?: return null
        if (!isKey) return null
        val (w, h) = _videoSize.value ?: (target.displayHint ?: (720 to 1280))
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
                // 低延迟（部分设备支持）
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            val mc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mc.configure(format, s, null, 0)
            mc.start()
            codec = mc
            configured = true
            mc
        } catch (e: Exception) {
            Log.e(TAG, "codec create failed: ${e.message}")
            _phase.value = ConnectionPhase.Failed("解码器初始化失败", retryable = true)
            null
        }
    }

    private fun releaseCodec() {
        val c = codec
        codec = null
        configured = false
        runCatching { c?.stop() }
        runCatching { c?.release() }
    }

    private fun tickStats() {
        val now = System.currentTimeMillis()
        val dt = (now - lastStatsMs).coerceAtLeast(1)
        val frames = frameCount.getAndSet(0)
        val bytes = byteCount.getAndSet(0)
        lastStatsMs = now
        val size = _videoSize.value
        _stats.value = _stats.value.copy(
            fps = (frames * 1000 / dt).toInt(),
            bitrateBps = bytes * 8 * 1000 / dt,
            width = size?.first ?: 0,
            height = size?.second ?: 0,
        )
    }

    companion object {
        private const val TAG = "WsStreamSession"
    }
}
