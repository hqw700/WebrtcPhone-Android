package com.webrtc_phone.android.rtc.signaling

import android.net.Uri
import android.util.Log
import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.core.net.HttpClientFactory
import com.webrtc_phone.android.rtc.StreamAuth
import com.webrtc_phone.android.rtc.StreamTarget
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Timer
import java.util.TimerTask

/**
 * 单次会话的信令 WebSocket。回调运行在 OkHttp 线程，由上层切换到会话线程处理。
 */
class SignalingClient(
    private val httpFactory: HttpClientFactory,
    private val target: StreamTarget,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onMessage(msg: SignalingMessage)
        /** 非主动关闭导致的断开；error 已归一化 */
        fun onClosed(error: ApiException?, httpCode: Int?)
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var closedByUs = false
    private var pinger: Timer? = null

    fun connect() {
        val ep = target.endpoint
        val builder = Request.Builder()
        when (val a = target.auth) {
            is StreamAuth.User -> builder.url(ep.wsUrl("/connect_client"))
                .header("Authorization", "Bearer ${a.token}")
            is StreamAuth.Share -> {
                // 服务端分享访客仅支持 query 参数鉴权（validateShareToken）
                var url = ep.wsUrl("/connect_client") + "?share_token=" + Uri.encode(a.shareToken)
                if (a.password.isNotEmpty()) url += "&share_pwd=" + Uri.encode(a.password)
                builder.url(url)
            }
        }
        ws = httpFactory.webSocketFor(ep).newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(SignalingProtocol.connect(target.deviceId))
                startPing(webSocket)
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = SignalingProtocol.parse(text) ?: return
                if (m is SignalingMessage.Pong) return
                listener.onMessage(m)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopPing()
                if (!closedByUs) listener.onClosed(null, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopPing()
                if (closedByUs) return
                Log.w(TAG, "signaling failure: ${t.message} http=${response?.code}")
                listener.onClosed(ApiException.from(t), response?.code)
            }
        })
    }

    fun send(text: String): Boolean = ws?.send(text) ?: false

    fun close() {
        closedByUs = true
        stopPing()
        ws?.close(1000, "client leave")
        ws = null
    }

    private fun startPing(socket: WebSocket) {
        stopPing()
        pinger = Timer("signaling-ping", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    socket.send(SignalingProtocol.PING)
                }
            }, PING_MS, PING_MS)
        }
    }

    private fun stopPing() {
        pinger?.cancel()
        pinger = null
    }

    companion object {
        private const val TAG = "Signaling"
        private const val PING_MS = 20_000L
    }
}
