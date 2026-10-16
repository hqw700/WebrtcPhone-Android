package com.webrtc_phone.android.rtc

import android.content.Context
import android.util.Log
import com.webrtc_phone.android.core.net.HttpClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.VideoSink
import java.util.concurrent.Executors

/**
 * 进程级投屏会话管理：
 * - 同一时刻只维护一个会话；投屏页重建（旋转/分屏）直接复用，秒开；
 * - 投屏页退到后台只“脱离”渲染，保活 [KEEP_ALIVE_MS] 后才真正断开；
 * - 可恢复错误（ICE 失败、超时、信令断开）在前台自动重连，指数退避，最多 [MAX_RETRIES] 次；
 * - 修改画质 / 切换摄像头 = 以新参数重建会话（agent 协议不支持运行时切换）。
 */
class SessionManager(
    private val context: Context,
    private val engine: RtcEngine,
    private val httpFactory: HttpClientFactory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "rtc-session").apply { priority = Thread.MAX_PRIORITY - 1 }
    }

    private val _session = MutableStateFlow<RtcSession?>(null)
    val session: StateFlow<RtcSession?> = _session.asStateFlow()

    /** 当前自动重连次数（0 = 未在重连） */
    private val _retry = MutableStateFlow(0)
    val retry: StateFlow<Int> = _retry.asStateFlow()

    private var watchJob: Job? = null
    private var keepAliveJob: Job? = null
    private var renderer: VideoSink? = null
    private var audioEnabled = true

    @Volatile var autoReconnect = true

    val eglContext get() = engine.eglBase.eglBaseContext

    /** 打开会话；与当前会话目标一致且仍存活时直接复用 */
    fun open(target: StreamTarget): RtcSession {
        keepAliveJob?.cancel()
        val cur = _session.value
        if (cur != null && cur.target == target && !cur.phase.value.terminal) return cur
        _retry.value = 0
        return replace(target)
    }

    /** 以新参数重建（画质/镜头变化或手动重连） */
    fun restart(target: StreamTarget? = null): RtcSession? {
        val t = target ?: _session.value?.target ?: return null
        _retry.value = 0
        return replace(t)
    }

    fun attach(sink: VideoSink) {
        keepAliveJob?.cancel()
        renderer = sink
        _session.value?.setRenderer(sink)
    }

    /** 投屏页不可见：停止渲染，保活一段时间后断开 */
    fun detach(sink: VideoSink?) {
        if (sink != null && renderer !== sink) return
        renderer = null
        _session.value?.setRenderer(null)
        keepAliveJob?.cancel()
        if (_session.value != null) {
            keepAliveJob = scope.launch {
                delay(KEEP_ALIVE_MS)
                Log.i(TAG, "keep-alive expired, closing session")
                close()
            }
        }
    }

    fun setAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
        _session.value?.setAudioEnabled(enabled)
    }

    fun close() {
        keepAliveJob?.cancel()
        watchJob?.cancel()
        _session.value?.close()
        _session.value = null
        _retry.value = 0
    }

    private fun replace(target: StreamTarget): RtcSession {
        watchJob?.cancel()
        _session.value?.close()
        val s = RtcSession(context, engine, httpFactory, target, executor)
        renderer?.let { s.setRenderer(it) }
        s.setAudioEnabled(audioEnabled)
        _session.value = s
        s.start()
        watchJob = scope.launch {
            s.phase.collect { p ->
                when {
                    p == ConnectionPhase.Connected -> _retry.value = 0
                    p is ConnectionPhase.Failed && p.retryable && shouldRetry() -> {
                        val attempt = _retry.value + 1
                        _retry.value = attempt
                        delay(RETRY_BASE_MS * (1L shl (attempt - 1)))
                        if (_session.value === s) {
                            Log.i(TAG, "auto reconnect #$attempt")
                            val keep = _retry.value
                            replace(target)
                            _retry.value = keep
                        }
                    }
                }
            }
        }
        return s
    }

    private fun shouldRetry() = autoReconnect && renderer != null && _retry.value < MAX_RETRIES

    companion object {
        private const val TAG = "SessionManager"
        private const val KEEP_ALIVE_MS = 5 * 60 * 1000L
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_MS = 1000L
    }
}
