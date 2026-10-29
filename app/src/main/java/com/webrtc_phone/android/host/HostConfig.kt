package com.webrtc_phone.android.host

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.UUID

enum class RunMode {
    ROOT,
    SHIZUKU;

    companion object {
        fun fromString(value: String?): RunMode {
            return if ("shizuku".equals(value, ignoreCase = true)) SHIZUKU else ROOT
        }
    }
}

data class HostPreferences(
    val signalingUrl: String = "",
    val deviceId: String = "",
    val iceServers: String = "",
    val runMode: RunMode = RunMode.ROOT,
    val autoStart: Boolean = false,
    val keepAlive: Boolean = true
)

object HostConfig {
    private const val PREF_NAME = "cloudphone_host_prefs"
    private const val KEY_SIGNALING = "host_signaling"
    private const val KEY_DEVICE_ID = "host_device_id"
    private const val KEY_ICE_SERVERS = "host_ice_servers"
    private const val KEY_RUN_MODE = "host_run_mode"
    private const val KEY_AUTO_START = "host_auto_start"
    private const val KEY_KEEP_ALIVE = "host_keep_alive"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun load(context: Context): HostPreferences {
        val sp = getPrefs(context)
        val defaultId = sp.getString("installation_device_id", null) ?: defaultDeviceId().also { id ->
            val editor = sp.edit().putString("installation_device_id", id)
            // 迁移旧版自动生成的固件指纹 ID，保留用户自定义 ID。
            val model = Build.MODEL.replace("\\s+".toRegex(), "_")
            val oldDefault = "$model-${(Build.FINGERPRINT.hashCode() and 0xFFFF).toString(16).padStart(4, '0')}"
            if (sp.getString(KEY_DEVICE_ID, null) == oldDefault) editor.putString(KEY_DEVICE_ID, id)
            editor.commit()
        }
        return HostPreferences(
            signalingUrl = sp.getString(KEY_SIGNALING, "") ?: "",
            deviceId = sp.getString(KEY_DEVICE_ID, defaultId).orEmpty().ifBlank { defaultId },
            iceServers = sp.getString(KEY_ICE_SERVERS, "") ?: "",
            runMode = RunMode.fromString(sp.getString(KEY_RUN_MODE, "root")),
            autoStart = RunMode.fromString(sp.getString(KEY_RUN_MODE, "root")) == RunMode.ROOT && sp.getBoolean(KEY_AUTO_START, false),
            keepAlive = sp.getBoolean(KEY_KEEP_ALIVE, true)
        )
    }

    fun save(context: Context, prefs: HostPreferences) {
        getPrefs(context).edit()
            .putString(KEY_SIGNALING, prefs.signalingUrl)
            .putString(KEY_DEVICE_ID, prefs.deviceId)
            .putString(KEY_ICE_SERVERS, prefs.iceServers)
            .putString(KEY_RUN_MODE, if (prefs.runMode == RunMode.SHIZUKU) "shizuku" else "root")
            .putBoolean(KEY_AUTO_START, prefs.autoStart && prefs.runMode == RunMode.ROOT)
            .putBoolean(KEY_KEEP_ALIVE, prefs.keepAlive)
            .apply()
    }

    fun isAutoStartEnabled(context: Context): Boolean {
        return load(context).let { it.autoStart && it.runMode == RunMode.ROOT }
    }

    fun defaultDeviceId(): String {
        val model = Build.MODEL.replace("\\s+".toRegex(), "_")
        return "${model}-${UUID.randomUUID()}"
    }
}
