package com.webrtc_phone.android.host

import android.app.KeyguardManager
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
import androidx.core.content.ContextCompat
import com.webrtc_phone.android.container
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

class ProjectionHostService : Service() {
    companion object {
        private const val STOP = "stop_projection"
        private const val CONSENT = "projection_consent"
        private const val TICKET = "projection_ticket"
        private const val CHANNEL = "projection_host"
        private const val NOTIFICATION = 4217
        fun start(context: Context, ticket: String, consent: Intent) {
            if (ProjectionStandbyService.signaling?.isPending(ticket) != true) return
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, ProjectionHostService::class.java)
                        .putExtra(TICKET, ticket).putExtra(CONSENT, consent)
                )
            } catch (e: Exception) {
                ProjectionStandbyService.signaling?.reject(ticket, "failed")
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
    private var ticket: String? = null
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
        val requestTicket = intent?.getStringExtra(TICKET)
        val signaling = ProjectionStandbyService.signaling
        sender = signaling
        ticket = requestTicket
        try {
            showNotification(requestTicket != null && signaling?.isUnattendedRequest(requestTicket) == true)
            check(consent != null && requestTicket != null && signaling?.isPending(requestTicket) == true) {
                "投屏请求已失效"
            }
            val engine = container.rtcEngine
            val factory = engine.factory()
            val videoSource = factory.createVideoSource(true).also { source = it }
            val helper = checkNotNull(SurfaceTextureHelper.create("ProjectionTexture", engine.eglBase.eglBaseContext))
            texture = helper
            val screen = ScreenCapturerAndroid(consent, object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post {
                        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
                        finish(
                            if (locked) "锁屏已结束屏幕共享，请解锁后重新授权"
                            else "系统已结束屏幕共享，请在本机重新授权"
                        )
                    }
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
            check(signaling.startShare(requestTicket, factory, videoTrack) { fullDisplay }) {
                "投屏请求已失效"
            }
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

    private fun displaySize(): Pair<Int, Int> = projectionDisplaySize(this)

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
    private fun showNotification(unattended: Boolean) {
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
            .setContentTitle(if (unattended) "亮屏无人值守已开启" else "正在共享屏幕")
            .setContentText(if (unattended) "请保持手机亮屏并解锁" else null)
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
        ticket?.let { sender?.captureEnded(it, message) }
        if (capturer != null) runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        track?.dispose()
        source?.dispose()
        texture?.dispose()
    }

    override fun onDestroy() {
        if (!closing) release("已停止共享屏幕")
        super.onDestroy()
    }
}
