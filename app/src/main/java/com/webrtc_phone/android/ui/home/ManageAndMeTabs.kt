package com.webrtc_phone.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.core.util.Formatters
import com.webrtc_phone.android.data.settings.ThemeMode
import com.webrtc_phone.android.ui.common.ConfirmDialog
import com.webrtc_phone.android.ui.common.Pill
import com.webrtc_phone.android.ui.common.SectionCard
import com.webrtc_phone.android.ui.common.SettingRow
import com.webrtc_phone.android.ui.theme.AppColors

object Routes {
    const val HOME = "home"
    const val SHARES = "shares"
    const val USERS = "users"
    const val TAGS = "tags"
    const val QUICK_TEXTS = "quick_texts"
    const val BATCH_FILES = "batch_files"
    const val STREAM_SETTINGS = "stream_settings"
    const val APP_SETTINGS = "app_settings"
    const val CERTS = "certs"
    const val ABOUT = "about"
    const val HOST = "host"
    fun shares(deviceId: String? = null, create: Boolean = false) =
        "$SHARES?deviceId=${deviceId.orEmpty()}&create=$create"
}

@Composable
fun ManageTab(vm: HomeViewModel, navigate: (String) -> Unit) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("设备总数", devices.size.toString(), Modifier.weight(1f))
            StatCard("在线", devices.count { it.online }.toString(), Modifier.weight(1f), AppColors.Online)
            StatCard("使用中", devices.count { it.online && it.inUse }.toString(), Modifier.weight(1f), AppColors.Busy)
        }
        SectionCard(title = "访问控制") {
            SettingRow("分享与卡密", "生成卡密/链接，管理访客访问", Icons.Outlined.Share, onClick = { navigate(Routes.shares()) })
            SettingRow("用户管理", "账号、有效期、权限、踢下线", Icons.Outlined.Group, onClick = { navigate(Routes.USERS) })
        }
        SectionCard(title = "设备组织") {
            SettingRow("标签管理", "${tags.tags.size} 个标签", Icons.Outlined.Label, onClick = { navigate(Routes.TAGS) })
            SettingRow("快捷文本", "投屏时一键下发常用文本", Icons.AutoMirrored.Outlined.ShortText, onClick = { navigate(Routes.QUICK_TEXTS) })
        }
        SectionCard(title = "批量操作") {
            SettingRow("批量文件分发", "上传文件后一键安装/推送到多台设备", Icons.Outlined.DriveFolderUpload, onClick = { navigate(Routes.BATCH_FILES) })
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MeTab(vm: HomeViewModel, navigate: (String) -> Unit) {
    val me by vm.me.collectAsStateWithLifecycle()
    val app by vm.appSettings.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    val s = vm.session

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Card(
            Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row {
                    Text(s.username, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Pill(if (s.isAdmin) "管理员" else "用户", if (s.isAdmin) AppColors.Brand else AppColors.Online, filled = true)
                }
                Spacer(Modifier.height(4.dp))
                Text(vm.endpoint.display, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                Spacer(Modifier.height(8.dp))
                val exp = me?.expiresAt
                Text(
                    if (exp == null) "账号长期有效" else "账号到期：${Formatters.dateTime(exp)}（${Formatters.relative(exp)}）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (exp != null && exp - System.currentTimeMillis() < 3 * 86400_000L) AppColors.Danger else MaterialTheme.colorScheme.onPrimaryContainer,
                )
                me?.leases?.takeIf { it.isNotEmpty() }?.let { leases ->
                    Spacer(Modifier.height(6.dp))
                    Text("设备租约 ${leases.size} 个，最近到期：${Formatters.duration(leases.minOf { it.remainingSeconds })}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionCard(title = "投屏") {
            SettingRow("画质与编码", "分辨率、帧率、码率、音频", Icons.Outlined.HighQuality, onClick = { navigate(Routes.STREAM_SETTINGS) })
            SettingRow("快捷文本", "管理文本下发模板", Icons.AutoMirrored.Outlined.ShortText, onClick = { navigate(Routes.QUICK_TEXTS) })
        }
        SectionCard(title = "受控") {
            SettingRow("本机受控端", "开启被控服务，提供远程投屏与控制", Icons.Outlined.PhoneAndroid, tint = AppColors.Brand, onClick = { navigate(Routes.HOST) })
        }
        SectionCard(title = "通用") {
            SettingRow(
                "外观", when (app.themeMode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" },
                Icons.Outlined.DarkMode, onClick = { navigate(Routes.APP_SETTINGS) }
            )
            SettingRow("偏好设置", "振动反馈、剪贴板同步、自动重连…", Icons.Outlined.Tune, onClick = { navigate(Routes.APP_SETTINGS) })
            SettingRow("安全与证书", "已信任的服务器证书", Icons.Outlined.Lock, onClick = { navigate(Routes.CERTS) })
            SettingRow("关于", null, Icons.Outlined.Info, onClick = { navigate(Routes.ABOUT) })
        }
        if (s.isAdmin) SectionCard(title = "管理") {
            SettingRow("进入管理中心", null, Icons.Outlined.AdminPanelSettings, onClick = { navigate("tab:manage") })
        }
        SectionCard {
            SettingRow("退出登录", null, Icons.AutoMirrored.Outlined.Logout, tint = AppColors.Danger, onClick = { confirmLogout = true })
        }
    }
    if (confirmLogout) ConfirmDialog(
        "退出登录", "将断开当前投屏并清除本机保存的登录凭据。", "退出", destructive = true,
        onConfirm = { vm.logout() }, onDismiss = { confirmLogout = false }
    )
}
