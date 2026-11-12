package com.webrtc_phone.android.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.webrtc_phone.android.container
import com.webrtc_phone.android.core.net.ServerEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

class ProjectionHostService : Service() {
    data class State(val active: Boolean = false, val online: Boolean = false, val message: String = "")

    companion object {
        private const val STOP = "stop_projection"
        private const val CONSENT = "projection_consent"
        private const val CHANNEL = "projection_host"
        private const val NOTIFICATION = 4217
        private val current = MutableStateFlow(State())
        val state = current.asStateFlow()

        fun start(context: Context, consent: Intent) {
            if (current.value.active || AgentHostService.isServiceRunning) return
            current.value = State(active = true, message = "正在启动系统录屏")
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, ProjectionHostService::class.java).putExtra(CONSENT, consent)
                )
            } catch (e: Exception) {
                current.value = State(message = "启动系统录屏失败：${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProjectionHostService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var capturer: ScreenCapturerAndroid? = null
    private var texture: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var sender: ProjectionSignaling? = null
    private var width = 0
    private var height = 0
    private var started = false
    private var fullDisplay = Build.VERSION.SDK_INT < 34
    private var closing = false

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            finish("已停止共享屏幕")
            return START_NOT_STICKY
        }
        if (capturer != null || closing) return START_NOT_STICKY
        val consent = intent?.getParcelableExtra<Intent>(CONSENT)
        try {
            check(consent != null && !AgentHostService.isServiceRunning) { "请重新授权共享屏幕" }
            showNotification()
            val prefs = HostConfig.load(this)
            val endpoint = requireNotNull(ServerEndpoint.parse(prefs.signalingUrl)) { "请填写有效的信令服务器地址" }
            val engine = container.rtcEngine
            val factory = engine.factory()
            val videoSource = factory.createVideoSource(true).also { source = it }
            val helper = checkNotNull(SurfaceTextureHelper.create("ProjectionTexture", engine.eglBase.eglBaseContext))
            texture = helper
            val screen = ScreenCapturerAndroid(consent, object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { finish("系统已结束屏幕共享，请在本机重新授权") }
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    main.post {
                        if (!closing) {
                            fullDisplay = displaySize() == (width to height)
                            resize(width, height)
                            if (!fullDisplay) sender?.resetGestures()
                        }
                    }
                }
            }).also { capturer = it }
            screen.initialize(helper, this, videoSource.capturerObserver)
            val (displayWidth, displayHeight) = displaySize()
            width = displayWidth
            height = displayHeight
            val (captureWidth, captureHeight) = captureSize(width, height)
            videoSource.adaptOutputFormat(captureWidth, captureHeight, 30)
            screen.startCapture(captureWidth, captureHeight, 30)
            started = true
            val videoTrack = factory.createVideoTrack("screen", videoSource).also { track = it }
            sender = ProjectionSignaling(
                container.httpFactory.webSocketFor(endpoint), endpoint, prefs, factory, videoTrack,
                main, ::displaySize, { fullDisplay },
                { online, message -> if (!closing) current.value = State(true, online, message) },
                ::finish
            ).also { it.connect() }
        } catch (e: Exception) {
            finish("启动系统录屏失败：${e.message}")
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < 34 && !closing) {
            val (w, h) = displaySize()
            resize(w, h)
        }
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Pair<Int, Int> {
        val manager = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = manager.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val size = android.graphics.Point()
        manager.defaultDisplay.getRealSize(size)
        return size.x to size.y
    }

    private fun resize(w: Int, h: Int) {
        if (!started || w <= 0 || h <= 0 || (w == width && h == height)) return
        width = w
        height = h
        val (captureWidth, captureHeight) = captureSize(w, h)
        source?.adaptOutputFormat(captureWidth, captureHeight, 30)
        capturer?.changeCaptureFormat(captureWidth, captureHeight, 30)
        sender?.resetGestures()
    }

    private fun captureSize(w: Int, h: Int): Pair<Int, Int> {
        val scale = minOf(1.0, 1280.0 / maxOf(w, h))
        return ((w * scale).roundToInt() and -2) to ((h * scale).roundToInt() and -2)
    }

    @Suppress("DEPRECATION")
    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "系统录屏被控", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ProjectionHostService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("正在共享屏幕")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止共享", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION, notification)
        }
    }

    private fun finish(message: String) {
        if (closing) return
        release(message)
        stopSelf()
    }

    private fun release(message: String) {
        closing = true
        sender?.close()
        if (capturer != null) runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        track?.dispose()
        source?.dispose()
        texture?.dispose()
        current.value = State(message = message)
    }

    override fun onDestroy() {
        if (!closing) release("已停止共享屏幕")
        super.onDestroy()
    }
}
