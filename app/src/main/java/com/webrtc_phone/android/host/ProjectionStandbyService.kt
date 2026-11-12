package com.webrtc_phone.android.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.webrtc_phone.android.container
import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.core.net.UntrustedCertificateInfo
import com.webrtc_phone.android.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 待协助服务仅维护设备注册，屏幕授权和视频资源由录屏服务管理。 */
class ProjectionStandbyService : Service() {
    data class State(val active: Boolean = false, val online: Boolean = false, val message: String = "", val unattended: Boolean = false)
    data class Request(val ticket: String, val requester: String, val control: Boolean, val authorizing: Boolean = false)

    companion object {
        private const val STANDBY_CHANNEL = "projection_standby"
        private const val REQUEST_CHANNEL = "projection_request"
        private const val STANDBY_NOTIFICATION = 4218
        private const val REQUEST_NOTIFICATION = 4219
        private const val CERTIFICATE_NOTIFICATION = 4220
        private const val STOP = "stop_standby"
        private val current = MutableStateFlow(State())
        private val pending = MutableStateFlow<Request?>(null)
        private val pendingCertificate = MutableStateFlow<UntrustedCertificateInfo?>(null)
        val certificate = pendingCertificate.asStateFlow()
        val state = current.asStateFlow()
        val request = pending.asStateFlow()
        internal var signaling: ProjectionSignaling? = null
            private set

        fun start(context: Context) {
            if (current.value.active || AgentHostService.isServiceRunning) return
            current.value = State(active = true, message = "正在注册设备")
            try {
                ContextCompat.startForegroundService(context, Intent(context, ProjectionStandbyService::class.java))
            } catch (e: Exception) {
                current.value = State(message = "待协助启动失败：${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProjectionStandbyService::class.java))
        }

        fun trustCertificate(context: Context, info: UntrustedCertificateInfo) {
            val connection = signaling ?: return
            if (pendingCertificate.value != info) return
            val endpoint = ServerEndpoint.parse(HostConfig.load(context).signalingUrl) ?: return
            if (endpoint.key != info.endpointKey) return
            if (info.notAfter <= System.currentTimeMillis()) {
                declineCertificate(context, info, "信令服务器证书已过期")
                return
            }
            context.container.pinStore.pin(endpoint.key, info)
            context.container.httpFactory.invalidate(endpoint)
            pendingCertificate.value = null
            context.getSystemService(NotificationManager::class.java).cancel(CERTIFICATE_NOTIFICATION)
            connection.connect()
        }

        fun declineCertificate(
            context: Context,
            info: UntrustedCertificateInfo,
            message: String = "已取消证书确认，在线协助已停止",
        ) {
            if (pendingCertificate.value != info) return
            pendingCertificate.value = null
            current.value = State(message = message)
            stop(context)
        }

        fun authorize(ticket: String): Boolean {
            val request = pending.value ?: return false
            if (request.ticket != ticket || request.authorizing) return false
            pending.value = request.copy(authorizing = true)
            return true
        }

        fun reject(ticket: String) {
            signaling?.reject(ticket)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var closing = false
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (signaling != null) return START_NOT_STICKY
        try {
            showStandbyNotification()
            val prefs = HostConfig.load(this)
            check(prefs.runMode == RunMode.PROJECTION && !AgentHostService.isServiceRunning)
            val endpoint = requireNotNull(ServerEndpoint.parse(prefs.signalingUrl)) { "请填写有效的信令服务器地址" }
            signaling = ProjectionSignaling(
                container.httpFactory.webSocketFor(endpoint), endpoint, prefs, main,
                { projectionDisplaySize(this) },
                { online, message -> if (!closing) current.value = State(true, online, message, signaling?.unattended == true) },
                ::publishRequest,
                { ProjectionHostService.stop(this) },
                ::requestCertificateConfirmation,
                { message -> current.value = State(message = message); stopSelf() }
            ).also { it.connect() }
        } catch (e: Exception) {
            current.value = State(message = "待协助启动失败：${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun showStandbyNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(STANDBY_CHANNEL, "在线待协助", NotificationManager.IMPORTANCE_LOW))
            notifications.createNotificationChannel(NotificationChannel(REQUEST_CHANNEL, "远程协助请求", NotificationManager.IMPORTANCE_HIGH))
        }
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = builder(STANDBY_CHANNEL).setContentTitle("WebrtcPhone 在线待协助")
            .setContentText("等待管理台发起投屏请求").setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止待命", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(STANDBY_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(STANDBY_NOTIFICATION, notification)
    }

    private fun requestCertificateConfirmation(info: UntrustedCertificateInfo) {
        current.value = State(active = true, message = "等待确认信令服务器证书")
        pendingCertificate.value = info
        val notification = builder(REQUEST_CHANNEL)
            .setContentTitle(if (info.changed) "信令服务器证书已变更" else "请确认信令服务器证书")
            .setContentText("打开 WebrtcPhone 核对证书指纹")
            .setAutoCancel(true).build()
        notifications.notify(CERTIFICATE_NOTIFICATION, notification)
    }

    private fun publishRequest(request: Request?) {
        pending.value = request
        if (request == null) {
            notifications.cancel(REQUEST_NOTIFICATION)
            return
        }
        val notification = builder(REQUEST_CHANNEL).setContentTitle("收到远程协助请求")
            .setContentText("${request.requester} 请求共享屏幕")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setTimeoutAfterCompat().build()
        notifications.notify(REQUEST_NOTIFICATION, notification)
    }

    private fun Notification.Builder.setTimeoutAfterCompat(): Notification.Builder {
        if (Build.VERSION.SDK_INT >= 26) setTimeoutAfter(60_000)
        return this
    }

    @Suppress("DEPRECATION")
    private fun builder(channel: String): Notification.Builder {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentIntent(open)
    }

    override fun onDestroy() {
        closing = true
        val old = signaling
        signaling = null
        old?.close()
        publishRequest(null)
        pendingCertificate.value = null
        notifications.cancel(CERTIFICATE_NOTIFICATION)
        ProjectionHostService.stop(this)
        current.value = State(message = current.value.message.takeUnless { current.value.active } ?: "已停止待协助")
        super.onDestroy()
    }
}

@Suppress("DEPRECATION")
internal fun projectionDisplaySize(context: Context): Pair<Int, Int> {
    val manager = context.getSystemService(WindowManager::class.java)
    if (Build.VERSION.SDK_INT >= 30) {
        val bounds = manager.maximumWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }
    val size = android.graphics.Point()
    manager.defaultDisplay.getRealSize(size)
    return size.x to size.y
}
