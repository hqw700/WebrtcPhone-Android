package com.webrtc_phone.android.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 画质档位：一键预设，对标主流云手机“流畅/标清/高清/超清/原画”。CUSTOM 为用户手动调参 */
enum class QualityPreset(
    val label: String,
    val maxSize: Int,
    val maxFps: Int,
    val bitrateMbps: Int,
) {
    SMOOTH("流畅", 720, 30, 2),
    STANDARD("标清", 1080, 30, 4),
    HIGH("高清", 1280, 60, 6),
    ULTRA("超清", 1920, 60, 10),
    ORIGINAL("原画", 0, 60, 16),
    CUSTOM("自定义", 0, 0, 0),
}

/** 投屏编码参数（下发给 agent 的 scrcpy_options） */
data class StreamSettings(
    val preset: QualityPreset = QualityPreset.STANDARD,
    /** 0 = 不限制 */
    val maxSize: Int = QualityPreset.STANDARD.maxSize,
    /** 0 = 不限制 */
    val maxFps: Int = QualityPreset.STANDARD.maxFps,
    val bitrateMbps: Int = QualityPreset.STANDARD.bitrateMbps,
    val bwe: Boolean = true,
    val minBitrateMbps: Int = 1,
    val maxBitrateMbps: Int = 20,
    val audio: Boolean = true,
    val powerOff: Boolean = false,
) {
    fun withPreset(p: QualityPreset): StreamSettings =
        if (p == QualityPreset.CUSTOM) copy(preset = p)
        else copy(preset = p, maxSize = p.maxSize, maxFps = p.maxFps, bitrateMbps = p.bitrateMbps)
}

/** 应用级偏好 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val gridColumns: Int = 2,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    val clipboardSync: Boolean = true,
    val showNavBar: Boolean = true,
    val showStats: Boolean = false,
    val autoReconnect: Boolean = true,
    /** 投屏时锁定方向：auto 跟随画面 */
    val orientationLock: String = "auto",
)

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _stream = MutableStateFlow(loadStream())
    val stream: StateFlow<StreamSettings> = _stream.asStateFlow()

    private val _app = MutableStateFlow(loadApp())
    val app: StateFlow<AppSettings> = _app.asStateFlow()

    @Synchronized
    fun updateStream(transform: (StreamSettings) -> StreamSettings) {
        val v = sanitize(transform(_stream.value))
        prefs.edit()
            .putString("s_preset", v.preset.name)
            .putInt("s_max_size", v.maxSize)
            .putInt("s_max_fps", v.maxFps)
            .putInt("s_bitrate", v.bitrateMbps)
            .putBoolean("s_bwe", v.bwe)
            .putInt("s_min_bitrate", v.minBitrateMbps)
            .putInt("s_max_bitrate", v.maxBitrateMbps)
            .putBoolean("s_audio", v.audio)
            .putBoolean("s_power_off", v.powerOff)
            .apply()
        _stream.value = v
    }

    @Synchronized
    fun updateApp(transform: (AppSettings) -> AppSettings) {
        val v = transform(_app.value).let { it.copy(gridColumns = it.gridColumns.coerceIn(1, 4)) }
        prefs.edit()
            .putString("a_theme", v.themeMode.name)
            .putInt("a_grid", v.gridColumns)
            .putBoolean("a_haptics", v.haptics)
            .putBoolean("a_keep_on", v.keepScreenOn)
            .putBoolean("a_clipboard", v.clipboardSync)
            .putBoolean("a_navbar", v.showNavBar)
            .putBoolean("a_stats", v.showStats)
            .putBoolean("a_reconnect", v.autoReconnect)
            .putString("a_orientation", v.orientationLock)
            .apply()
        _app.value = v
    }

    fun resetStream() = updateStream { StreamSettings() }

    private fun sanitize(s: StreamSettings): StreamSettings {
        val min = s.minBitrateMbps.coerceIn(1, 50)
        val max = s.maxBitrateMbps.coerceIn(min, 100)
        return s.copy(
            maxSize = s.maxSize.coerceIn(0, 4096),
            maxFps = s.maxFps.coerceIn(0, 144),
            bitrateMbps = s.bitrateMbps.coerceIn(1, 100),
            minBitrateMbps = min,
            maxBitrateMbps = max,
        )
    }

    private fun loadStream(): StreamSettings {
        val d = StreamSettings()
        return StreamSettings(
            preset = runCatching { QualityPreset.valueOf(prefs.getString("s_preset", d.preset.name)!!) }
                .getOrDefault(d.preset),
            maxSize = prefs.getInt("s_max_size", d.maxSize),
            maxFps = prefs.getInt("s_max_fps", d.maxFps),
            bitrateMbps = prefs.getInt("s_bitrate", d.bitrateMbps),
            bwe = prefs.getBoolean("s_bwe", d.bwe),
            minBitrateMbps = prefs.getInt("s_min_bitrate", d.minBitrateMbps),
            maxBitrateMbps = prefs.getInt("s_max_bitrate", d.maxBitrateMbps),
            audio = prefs.getBoolean("s_audio", d.audio),
            powerOff = prefs.getBoolean("s_power_off", d.powerOff),
        )
    }

    private fun loadApp(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString("a_theme", d.themeMode.name)!!) }
                .getOrDefault(d.themeMode),
            gridColumns = prefs.getInt("a_grid", d.gridColumns),
            haptics = prefs.getBoolean("a_haptics", d.haptics),
            keepScreenOn = prefs.getBoolean("a_keep_on", d.keepScreenOn),
            clipboardSync = prefs.getBoolean("a_clipboard", d.clipboardSync),
            showNavBar = prefs.getBoolean("a_navbar", d.showNavBar),
            showStats = prefs.getBoolean("a_stats", d.showStats),
            autoReconnect = prefs.getBoolean("a_reconnect", d.autoReconnect),
            orientationLock = prefs.getString("a_orientation", d.orientationLock) ?: d.orientationLock,
        )
    }
}
