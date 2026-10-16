package com.webrtc_phone.android.data.realtime

import android.util.Base64
import android.util.Log
import com.webrtc_phone.android.core.net.HttpClientFactory
import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.Parsers
import com.webrtc_phone.android.data.model.TagsPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.min

sealed interface DashboardEvent {
    data class Devices(val devices: List<Device>) : DashboardEvent
    /** WS 直推的 JPEG 快照 */
    data class SnapshotData(val deviceId: String, val jpeg: ByteArray) : DashboardEvent
    /** HTTP 模式快照更新通知，需要按 hash 拉取 */
    data class SnapshotUpdated(val deviceId: String, val hash: String) : DashboardEvent
    data class Tags(val payload: TagsPayload) : DashboardEvent
    data class ScreenState(val deviceId: String, val state: String) : DashboardEvent
}

enum class SocketState { IDLE, CONNECTING, CONNECTED, RECONNECTING }

/**
 * 仪表盘实时通道（/connect_client 不发 connect 消息即为纯订阅模式）：
 * 设备上下线、快照、标签变更全部推送，替代旧版 10s 轮询。
 * 断线指数退避重连（1s → 30s），应用层 20s ping 防 NAT/反代超时。
 */
class DashboardSocket(
    private val httpFactory: HttpClientFactory,
    private val endpoint: ServerEndpoint,
    private val tokenProvider: () -> String?,
    private val onUnauthorized: () -> Unit,
) {
    private val _state = MutableStateFlow(SocketState.IDLE)
    val state: StateFlow<SocketState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<DashboardEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<DashboardEvent> = _events.asSharedFlow()

    private var job: Job? = null

    @Volatile
    private var ws: WebSocket? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                _state.value = if (attempt == 0) SocketState.CONNECTING else SocketState.RECONNECTING
                val result = runOnce()
                if (result == CloseResult.UNAUTHORIZED) {
                    onUnauthorized()
                    break
                }
                if (result == CloseResult.OPENED_THEN_CLOSED) attempt = 0
                attempt++
                delay(min(30_000L, 1_000L shl min(attempt - 1, 5)))
            }
            _state.value = SocketState.IDLE
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        ws?.close(1000, "bye")
        ws = null
        _state.value = SocketState.IDLE
    }

    private enum class CloseResult { FAILED, OPENED_THEN_CLOSED, UNAUTHORIZED }

    private suspend fun runOnce(): CloseResult = coroutineScope {
        val token = tokenProvider() ?: return@coroutineScope CloseResult.UNAUTHORIZED
        val request = Request.Builder()
            .url(endpoint.wsUrl("/connect_client"))
            .header("Authorization", "Bearer $token")
            .build()
        // 应用层心跳：服务端 pongWait 依赖客户端消息续期
        val pinger = launch {
            while (isActive) {
                delay(20_000)
                ws?.send("{\"type\":\"ping\"}")
            }
        }
        try {
            suspendCancellableCoroutine { cont ->
                var opened = false
                val socket = httpFactory.webSocketFor(endpoint).newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened = true
                        _state.value = SocketState.CONNECTED
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handle(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (cont.isActive) cont.resume(if (opened) CloseResult.OPENED_THEN_CLOSED else CloseResult.FAILED)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "dashboard ws failure: ${t.message} code=${response?.code}")
                        val r = when {
                            response?.code == 401 -> CloseResult.UNAUTHORIZED
                            opened -> CloseResult.OPENED_THEN_CLOSED
                            else -> CloseResult.FAILED
                        }
                        if (cont.isActive) cont.resume(r)
                    }
                })
                ws = socket
                cont.invokeOnCancellation { socket.cancel() }
            }
        } finally {
            pinger.cancel()
            ws = null
        }
    }

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = msg.optString("message_type").ifEmpty { msg.optString("type") }
        val event: DashboardEvent? = when (type) {
            "device_list_update" -> DashboardEvent.Devices(Parsers.devices(msg.optJSONArray("devices")))
            "snapshot_update" -> {
                val data = msg.optString("data")
                val bytes = runCatching { Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT) }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) DashboardEvent.SnapshotData(msg.optString("device_id"), bytes) else null
            }
            "snapshot_updated" -> DashboardEvent.SnapshotUpdated(msg.optString("device_id"), msg.optString("hash"))
            "tags_update" -> DashboardEvent.Tags(Parsers.tags(msg))
            "device_screen_state" -> DashboardEvent.ScreenState(msg.optString("device_id"), msg.optString("screen_state"))
            else -> null
        }
        if (event != null) _events.tryEmit(event)
    }

    companion object {
        private const val TAG = "DashboardSocket"
    }
}
