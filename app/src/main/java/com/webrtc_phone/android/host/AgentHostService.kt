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
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.webrtc_phone.android.R
import com.webrtc_phone.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin

class AgentHostService : Service {

    constructor() : super()

    companion object {
        private const val TAG = "AgentHostService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "cloudphone_host_channel"

        const val ACTION_START = "com.webrtc_phone.android.host.action.START"
        const val ACTION_STOP = "com.webrtc_phone.android.host.action.STOP"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var lastError: String? = null
            private set

        fun start(context: Context) {
            lastError = null
            val intent = Intent(context, AgentHostService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentHostService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var watchdogJob: Job? = null
    private var runningPrefs: HostPreferences? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AgentHostService onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.i(TAG, "onStartCommand action: $action")

        if (ACTION_STOP == action) {
            stopForegroundDaemon()
            return START_NOT_STICKY
        }

        if (isServiceRunning || stopping) return START_STICKY
        val prefs = HostConfig.load(this)
        runningPrefs = prefs
        if (prefs.keepAlive) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }

        val notification = buildNotification("正在启动受控端...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isServiceRunning = true

        startWatchdog(prefs)
        return START_STICKY
    }

    private fun startWatchdog(prefs: HostPreferences) {
        watchdogJob = scope.launch {
            val useShizuku = prefs.runMode == RunMode.SHIZUKU
            while (isActive) {
                try {
                    val pid = AgentController.isAgentRunning(useShizuku)
                    if (pid.isNotEmpty()) {
                        lastError = null
                        updateNotification("运行中 [PID: $pid] · 设备: ${prefs.deviceId}")
                    } else {
                        updateNotification("Agent 未运行，尝试拉起中...")
                        AgentController.startAgent(
                            applicationContext,
                            useShizuku,
                            prefs.signalingUrl,
                            prefs.deviceId,
                            prefs.iceServers
                        )
                    }
                } catch (e: Exception) {
                    if (!isActive) throw e
                    lastError = "启动失败: ${e.message}"
                    updateNotification(lastError!!)
                    Log.w(TAG, "Watchdog failed: ${e.message}")
                }
                delay(5000)
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cloudphone:host_wakelock")?.apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(12 * 60 * 60 * 1000L) // 最大持有 12 小时安全上限
                Log.d(TAG, "WakeLock acquired")
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun buildNotification(statusText: String): Notification {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopIntent = Intent(this, AgentHostService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle("云手机受控端 (Host Agent)")
            .setContentText(statusText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(0, "停止受控", stopPendingIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "云手机受控端保活",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持受控端在后台持续运行"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundDaemon() {
        if (stopping) return
        stopping = true
        scope.launch {
            // 等待当前部署/启动结束后再杀进程，避免停止后又被在途启动拉起。
            watchdogJob?.cancelAndJoin()
            AgentController.stopAgent((runningPrefs ?: HostConfig.load(this@AgentHostService)).runMode == RunMode.SHIZUKU)
            isServiceRunning = false
            lastError = null
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        isServiceRunning = false
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
