package com.webrtc_phone.android.ui.stream

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ContentPasteGo
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material.icons.outlined.VolumeUp
import com.webrtc_phone.android.rtc.channel.Keys

/** 工具面板内容；按会话权限动态禁用 */
fun toolSections(
    vm: StreamViewModel,
    connected: Boolean,
    onScreenshot: () -> Unit,
    onExit: () -> Unit,
): List<Pair<String, List<ToolItem>>> {
    val t = vm.target
    val control = connected && !t.viewOnly
    fun close() {
        vm.panel = Panel.NONE
    }
    return listOf(
        "设备控制" to listOf(
            ToolItem("电源", Icons.Outlined.PowerSettingsNew, control, onLongClick = { vm.longPress(Keys.POWER) }) { vm.press(Keys.POWER) },
            ToolItem("音量+", Icons.Outlined.VolumeUp, control) { vm.press(Keys.VOLUME_UP) },
            ToolItem("音量-", Icons.Outlined.VolumeDown, control) { vm.press(Keys.VOLUME_DOWN) },
            ToolItem("通知栏", Icons.Outlined.Notifications, control) { vm.press(Keys.NOTIFICATION) },
            ToolItem("截图", Icons.Outlined.Screenshot, connected) { close(); onScreenshot() },
        ),
        "输入" to listOf(
            ToolItem("键盘输入", Icons.Outlined.Keyboard, control, active = vm.keyboardMode) { close(); vm.setKeyboard(!vm.keyboardMode) },
            ToolItem("文本下发", Icons.AutoMirrored.Outlined.ShortText, control) { vm.panel = Panel.TEXT },
            ToolItem("发送剪贴板", Icons.Outlined.ContentPasteGo, control && t.allowClipboard) { close(); vm.syncLocalClipboard(vm.appContext(), force = true) },
            ToolItem("获取剪贴板", Icons.Outlined.ContentPaste, control && t.allowClipboard) { close(); vm.pullRemoteClipboard() },
        ),
        "工具" to listOf(
            ToolItem("文件", Icons.Outlined.Folder, connected && t.allowFileTx && !t.viewOnly) { vm.panel = Panel.FILES },
            ToolItem("安装应用", Icons.Outlined.Apps, connected && t.allowFileTx && !t.viewOnly) { vm.panel = Panel.APPS },
            // 终端需协商完成后才创建 WebView（见 TerminalWebView 注释）
            ToolItem("终端", Icons.Outlined.Terminal, connected && t.allowTerminal && !t.viewOnly) {
                vm.terminalCreated = true
                vm.panel = Panel.TERMINAL
            },
        ),
        "画面与连接" to listOf(
            ToolItem("画质", Icons.Outlined.HighQuality, true) { vm.panel = Panel.QUALITY },
            ToolItem(if (vm.audioOn) "声音开" else "已静音", if (vm.audioOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, true, active = !vm.audioOn) { vm.toggleAudio() },
            ToolItem("性能", Icons.Outlined.Insights, true, active = vm.settings.app.value.showStats) {
                vm.settings.updateApp { it.copy(showStats = !it.showStats) }
            },
            ToolItem("连接信息", Icons.Outlined.Info, true) { vm.panel = Panel.INFO },
            ToolItem("重新连接", Icons.Outlined.Refresh, true) { close(); vm.reconnect() },
            @Suppress("DEPRECATION")
            ToolItem("断开", Icons.Outlined.Logout, true) { close(); onExit() },
        ),
    )
}
