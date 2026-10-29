package com.webrtc_phone.android.host

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

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

    fun load(context: Context): HostPreferences {
        val sp = getPrefs(context)
        val defaultId = defaultDeviceId()
        return HostPreferences(
            signalingUrl = sp.getString(KEY_SIGNALING, "") ?: "",
            deviceId = sp.getString(KEY_DEVICE_ID, defaultId) ?: defaultId,
            iceServers = sp.getString(KEY_ICE_SERVERS, "") ?: "",
            runMode = RunMode.fromString(sp.getString(KEY_RUN_MODE, "root")),
            autoStart = sp.getBoolean(KEY_AUTO_START, false),
            keepAlive = sp.getBoolean(KEY_KEEP_ALIVE, true)
        )
    }

    fun save(context: Context, prefs: HostPreferences) {
        getPrefs(context).edit()
            .putString(KEY_SIGNALING, prefs.signalingUrl)
            .putString(KEY_DEVICE_ID, prefs.deviceId)
            .putString(KEY_ICE_SERVERS, prefs.iceServers)
            .putString(KEY_RUN_MODE, if (prefs.runMode == RunMode.SHIZUKU) "shizuku" else "root")
            .putBoolean(KEY_AUTO_START, prefs.autoStart)
            .putBoolean(KEY_KEEP_ALIVE, prefs.keepAlive)
            .apply()
    }

    fun isAutoStartEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_START, false)
    }

    fun defaultDeviceId(): String {
        val model = Build.MODEL.replace("\\s+".toRegex(), "_")
        val serial = try {
            Build.getSerial()
        } catch (_: Throwable) {
            Build.UNKNOWN
        }
        val suffix = if (serial.isNotBlank() && serial != Build.UNKNOWN) {
            serial.takeLast(6)
        } else {
            (Build.FINGERPRINT.hashCode() and 0xFFFF).toString(16).padStart(4, '0')
        }
        return "${model}-$suffix"
    }
}
