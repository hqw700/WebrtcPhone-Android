package com.webrtc_phone.android.host

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.webrtc_phone.android.WebrtcPhoneApp
import java.util.UUID

enum class RunMode {
    ROOT,
    SHIZUKU,
    PROJECTION;

    companion object {
        fun fromString(value: String?): RunMode {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ROOT
        }
    }
}

data class HostPreferences(
    val signalingUrl: String = "",
    val deviceId: String = "",
    val iceServers: String = "",
    val runMode: RunMode = RunMode.ROOT,
    val autoStart: Boolean = false,
    val keepAlive: Boolean = true,
    val standalone: Boolean = false,
    val standalonePort: Int = HostConfig.DEFAULT_STANDALONE_PORT,
    val standalonePassword: String = "",
)

object HostConfig {
    const val DEFAULT_STANDALONE_PORT = 8443
    private const val PREF_NAME = "cloudphone_host_prefs"
    private const val KEY_SIGNALING = "host_signaling"
    private const val KEY_DEVICE_ID = "host_device_id"
    private const val KEY_ICE_SERVERS = "host_ice_servers"
    private const val KEY_RUN_MODE = "host_run_mode"
    private const val KEY_AUTO_START = "host_auto_start"
    private const val KEY_KEEP_ALIVE = "host_keep_alive"
    private const val KEY_STANDALONE = "host_standalone"
    private const val KEY_STANDALONE_PORT = "host_standalone_port"
    private const val SECURE_KEY_STANDALONE_PWD = "host_standalone_password"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun secureStore(context: Context) =
        runCatching { (context.applicationContext as? WebrtcPhoneApp)?.container?.secureStore }.getOrNull()

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
        val standalone = sp.getBoolean(KEY_STANDALONE, false)
        val rawMode = RunMode.fromString(sp.getString(KEY_RUN_MODE, "root"))
        val effectiveMode = if (standalone && rawMode == RunMode.PROJECTION) RunMode.ROOT else rawMode
        val port = sp.getInt(KEY_STANDALONE_PORT, DEFAULT_STANDALONE_PORT).takeIf { it in 1024..65535 } ?: DEFAULT_STANDALONE_PORT
        val password = runCatching { secureStore(context)?.getString(SECURE_KEY_STANDALONE_PWD) }.getOrNull().orEmpty()
        return HostPreferences(
            signalingUrl = sp.getString(KEY_SIGNALING, "") ?: "",
            deviceId = sp.getString(KEY_DEVICE_ID, defaultId).orEmpty().ifBlank { defaultId },
            iceServers = sp.getString(KEY_ICE_SERVERS, "") ?: "",
            runMode = effectiveMode,
            autoStart = effectiveMode == RunMode.ROOT && sp.getBoolean(KEY_AUTO_START, false),
            keepAlive = sp.getBoolean(KEY_KEEP_ALIVE, true),
            standalone = standalone,
            standalonePort = port,
            standalonePassword = password,
        )
    }

    fun save(context: Context, prefs: HostPreferences) {
        val effectiveMode = if (prefs.standalone && prefs.runMode == RunMode.PROJECTION) RunMode.ROOT else prefs.runMode
        val port = prefs.standalonePort.takeIf { it in 1024..65535 } ?: DEFAULT_STANDALONE_PORT
        getPrefs(context).edit()
            .putString(KEY_SIGNALING, prefs.signalingUrl)
            .putString(KEY_DEVICE_ID, prefs.deviceId)
            .putString(KEY_ICE_SERVERS, prefs.iceServers)
            .putString(KEY_RUN_MODE, effectiveMode.name.lowercase())
            .putBoolean(KEY_AUTO_START, prefs.autoStart && effectiveMode == RunMode.ROOT)
            .putBoolean(KEY_KEEP_ALIVE, prefs.keepAlive)
            .putBoolean(KEY_STANDALONE, prefs.standalone)
            .putInt(KEY_STANDALONE_PORT, port)
            .apply()
        runCatching {
            val store = secureStore(context) ?: return@runCatching
            if (prefs.standalonePassword.isEmpty()) store.remove(SECURE_KEY_STANDALONE_PWD)
            else store.putString(SECURE_KEY_STANDALONE_PWD, prefs.standalonePassword)
        }
    }

    fun isAutoStartEnabled(context: Context): Boolean {
        return load(context).let { it.autoStart && it.runMode == RunMode.ROOT }
    }

    fun defaultDeviceId(): String {
        val model = Build.MODEL.replace("\\s+".toRegex(), "_")
        return "${model}-${UUID.randomUUID()}"
    }
}
