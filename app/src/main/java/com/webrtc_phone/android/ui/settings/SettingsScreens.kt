package com.webrtc_phone.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.BuildConfig
import com.webrtc_phone.android.core.net.CertificatePinStore
import com.webrtc_phone.android.core.util.Formatters
import com.webrtc_phone.android.data.model.UserPolicy
import com.webrtc_phone.android.data.settings.QualityPreset
import com.webrtc_phone.android.data.settings.SettingsRepository
import com.webrtc_phone.android.data.settings.StreamSettings
import com.webrtc_phone.android.data.settings.ThemeMode
import com.webrtc_phone.android.ui.common.ConfirmDialog
import com.webrtc_phone.android.ui.common.EmptyState
import com.webrtc_phone.android.ui.common.SectionCard
import com.webrtc_phone.android.ui.common.SettingRow
import com.webrtc_phone.android.ui.common.SwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    snackbar: @Composable () -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = floating,
        snackbarHost = snackbar,
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

/** 画质设置（投屏页内面板复用 [StreamQualityEditor]） */
@Composable
fun StreamSettingsScreen(repo: SettingsRepository, policy: UserPolicy?, onBack: () -> Unit) {
    val s by repo.stream.collectAsStateWithLifecycle()
    SubScreen("画质与编码", onBack, actions = { TextButton(onClick = { repo.resetStream() }) { Text("恢复默认") } }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            StreamQualityEditor(s, policy, onChange = { t -> repo.updateStream(t) })
            Text(
                "设置在下次连接时生效；投屏中修改会自动快速重连。管理员禁止修改的项目以服务端配置为准。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StreamQualityEditor(
    s: StreamSettings,
    policy: UserPolicy?,
    onChange: ((StreamSettings) -> StreamSettings) -> Unit,
    dark: Boolean = false,
) {
    val lockRes = policy?.forbidResolution == true
    val lockFps = policy?.forbidFps == true
    val lockBitrate = policy?.forbidBitrate == true
    val lockAudio = policy?.forbidAudio == true
    val presetLocked = lockRes || lockFps || lockBitrate
    SectionCard(title = "画质档位") {
        FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QualityPreset.entries.filter { it != QualityPreset.CUSTOM }.forEach { p ->
                FilterChip(
                    selected = s.preset == p, enabled = !presetLocked,
                    onClick = { onChange { it.withPreset(p) } },
                    label = { Text(p.label + if (p.maxSize > 0) " ${p.maxSize}p" else "") }
                )
            }
        }
    }
    SectionCard(title = "高级参数") {
        SliderRow(
            "最大分辨率（长边）", s.maxSize.toFloat(), 0f..2560f, 8,
            label = if (s.maxSize == 0) "原始" else "${s.maxSize}px", enabled = !lockRes,
        ) { v -> onChange { it.copy(maxSize = snapSize(v), preset = QualityPreset.CUSTOM) } }
        SliderRow(
            "最大帧率", s.maxFps.toFloat(), 0f..120f, 7,
            label = if (s.maxFps == 0) "不限" else "${s.maxFps} fps", enabled = !lockFps,
        ) { v -> onChange { it.copy(maxFps = (v / 15).toInt() * 15, preset = QualityPreset.CUSTOM) } }
        SliderRow(
            "码率", s.bitrateMbps.toFloat(), 1f..40f, 38, label = "${s.bitrateMbps} Mbps", enabled = !lockBitrate,
        ) { v -> onChange { it.copy(bitrateMbps = v.toInt(), preset = QualityPreset.CUSTOM) } }
        SwitchRow(
            "动态码率 (BWE)", s.bwe, { b -> onChange { it.copy(bwe = b) } },
            subtitle = "根据网络状况自动调节 ${s.minBitrateMbps}–${s.maxBitrateMbps} Mbps，弱网更流畅",
            enabled = !lockBitrate,
        )
        if (s.bwe) {
            SliderRow("最低码率", s.minBitrateMbps.toFloat(), 1f..20f, 18, "${s.minBitrateMbps} Mbps", !lockBitrate) { v ->
                onChange { it.copy(minBitrateMbps = v.toInt()) }
            }
            SliderRow("最高码率", s.maxBitrateMbps.toFloat(), 2f..60f, 57, "${s.maxBitrateMbps} Mbps", !lockBitrate) { v ->
                onChange { it.copy(maxBitrateMbps = v.toInt()) }
            }
        }
        SwitchRow("传输声音", s.audio, { b -> onChange { it.copy(audio = b) } }, subtitle = "Opus 音频，关闭可节省约 128Kbps", enabled = !lockAudio)
    }
}

private fun snapSize(v: Float): Int {
    val steps = intArrayOf(0, 480, 720, 1080, 1280, 1600, 1920, 2560)
    return steps.minBy { kotlin.math.abs(it - v) }
}

@Composable
private fun SliderRow(
    title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, label: String,
    enabled: Boolean, onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(label + if (!enabled) " 🔒" else "", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) },
            valueRange = range, steps = steps, enabled = enabled
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppSettingsScreen(repo: SettingsRepository, onBack: () -> Unit) {
    val a by repo.app.collectAsStateWithLifecycle()
    SubScreen("偏好设置", onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionCard(title = "外观") {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEach { (m, l) ->
                        FilterChip(selected = a.themeMode == m, onClick = { repo.updateApp { it.copy(themeMode = m) } }, label = { Text(l) })
                    }
                }
            }
            SectionCard(title = "投屏") {
                SwitchRow("显示虚拟导航键", a.showNavBar, { b -> repo.updateApp { it.copy(showNavBar = b) } }, "画面下方显示 返回/主页/多任务", Icons.Outlined.ViewAgenda)
                SwitchRow("显示性能指标", a.showStats, { b -> repo.updateApp { it.copy(showStats = b) } }, "延迟、帧率、码率、丢包实时浮层", Icons.Outlined.Insights)
                SwitchRow("投屏时保持亮屏", a.keepScreenOn, { b -> repo.updateApp { it.copy(keepScreenOn = b) } }, null, Icons.Outlined.ScreenLockPortrait)
                SwitchRow("触控振动反馈", a.haptics, { b -> repo.updateApp { it.copy(haptics = b) } }, null, Icons.Outlined.Vibration)
                SwitchRow("剪贴板双向同步", a.clipboardSync, { b -> repo.updateApp { it.copy(clipboardSync = b) } }, "本机复制自动同步到云手机，反之亦然", Icons.Outlined.ContentPaste)
                SwitchRow("断线自动重连", a.autoReconnect, { b -> repo.updateApp { it.copy(autoReconnect = b) } }, "网络切换/抖动后自动恢复（最多 3 次）", Icons.Outlined.Sync)
            }
            SectionCard(title = "屏幕方向") {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "跟随画面", "portrait" to "竖屏", "landscape" to "横屏", "sensor" to "跟随重力").forEach { (v, l) ->
                        FilterChip(selected = a.orientationLock == v, onClick = { repo.updateApp { it.copy(orientationLock = v) } }, label = { Text(l) })
                    }
                }
            }
        }
    }
}

@Composable
fun CertificatesScreen(store: CertificatePinStore, onBack: () -> Unit) {
    val pins by store.pins.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<String?>(null) }
    SubScreen("安全与证书", onBack) { pad ->
        if (pins.isEmpty()) {
            EmptyState(Icons.Outlined.Lock, "没有手动信任的证书", "使用系统受信任证书（如 Let's Encrypt）的服务器无需在此管理", Modifier.padding(pad))
        } else LazyColumn(Modifier.padding(pad)) {
            item {
                Text(
                    "以下自签证书由你手动核对指纹后信任。移除后再次连接需要重新确认。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            items(pins, key = { it.endpointKey }) { p ->
                SectionCard {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(p.endpointKey, style = MaterialTheme.typography.titleSmall)
                            if (p.subject.isNotBlank()) Text(p.subject, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Text(p.sha256, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            if (p.pinnedAt > 0) Text("信任于 ${Formatters.dateTime(p.pinnedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { removing = p.endpointKey }) { Icon(Icons.Outlined.Delete, "移除") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    removing?.let { key ->
        ConfirmDialog("移除信任", "移除 $key 的证书信任？", "移除", destructive = true,
            onConfirm = { store.remove(key) }, onDismiss = { removing = null })
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    SubScreen("关于", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionCard {
                SettingRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                SettingRow("包名", BuildConfig.APPLICATION_ID)
                SettingRow("技术架构", "WebRTC P2P · H.264 硬解 · Opus · DataChannel")
            }
            SectionCard(title = "安全说明") {
                Text(
                    "· 登录凭据使用 Android Keystore 硬件密钥加密存储，不参与系统备份\n" +
                        "· 仅信任系统 CA；自签证书需手动核对 SHA-256 指纹后固定，证书变更会告警\n" +
                        "· 音视频与控制指令经 DTLS-SRTP 端到端加密传输\n" +
                        "· 卡密访客默认禁用终端，仅观看模式下客户端与服务端双重拦截输入",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            SectionCard(title = "开源许可") {
                SettingRow("WebRTC", "BSD-3-Clause")
                SettingRow("OkHttp", "Apache-2.0")
                SettingRow("xterm.js", "MIT")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
