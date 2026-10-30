package com.webrtc_phone.android.ui.stream

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ContentPasteGo
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.SwapHoriz
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
    val ws = t.isWs
    fun close() {
        vm.panel = Panel.NONE
    }
    return listOf(
        "设备控制" to listOf(
            @Suppress("DEPRECATION")
            ToolItem("断开", Icons.Outlined.Logout, true) { close(); onExit() },
            ToolItem("电源", Icons.Outlined.PowerSettingsNew, control, onLongClick = { vm.longPress(Keys.POWER) }) { vm.press(Keys.POWER) },
            ToolItem(
                label = if (vm.powerOff) "息屏中" else "息屏连接",
                icon = Icons.Outlined.DarkMode,
                enabled = connected && !t.isCamera,
                active = vm.powerOff,
            ) {
                close()
                vm.togglePowerOff()
            },
            ToolItem("音量+", Icons.Outlined.VolumeUp, control) { vm.press(Keys.VOLUME_UP) },
            ToolItem("音量-", Icons.Outlined.VolumeDown, control) { vm.press(Keys.VOLUME_DOWN) },
            ToolItem("通知栏", Icons.Outlined.Notifications, control) { vm.press(Keys.NOTIFICATION) },
            // WS 模式无法截图（无 GL 渲染器）
            ToolItem("截图", Icons.Outlined.Screenshot, connected && !ws) { close(); onScreenshot() },
        ),
        "输入" to listOf(
            ToolItem("键盘输入", Icons.Outlined.Keyboard, control, active = vm.keyboardMode) { close(); vm.setKeyboard(!vm.keyboardMode) },
            ToolItem("文本下发", Icons.AutoMirrored.Outlined.ShortText, control) { vm.panel = Panel.TEXT },
            // WS 模式无剪贴板通道
            ToolItem("发送剪贴板", Icons.Outlined.ContentPasteGo, control && t.allowClipboard && !ws) { close(); vm.syncLocalClipboard(vm.appContext(), force = true) },
            ToolItem("获取剪贴板", Icons.Outlined.ContentPaste, control && t.allowClipboard && !ws) { close(); vm.pullRemoteClipboard() },
        ),
        "工具" to listOf(
            // WS 模式无文件/终端通道（DataChannel 专属）
            ToolItem("文件", Icons.Outlined.Folder, connected && t.allowFileTx && !t.viewOnly && !ws) { vm.panel = Panel.FILES },
            ToolItem("安装应用", Icons.Outlined.Apps, connected && t.allowFileTx && !t.viewOnly && !ws) { vm.panel = Panel.APPS },
            // 终端需协商完成后才创建 WebView（见 TerminalWebView 注释）
            ToolItem("终端", Icons.Outlined.Terminal, connected && t.allowTerminal && !t.viewOnly && !ws) {
                vm.terminalCreated = true
                vm.panel = Panel.TERMINAL
            },
        ),
        "画面与连接" to listOfNotNull(
            if (vm.canSwitchDevices) ToolItem("切换设备", Icons.Outlined.SwapHoriz, true) { vm.openDrawer() } else null,
            ToolItem("画质", Icons.Outlined.HighQuality, true) { vm.panel = Panel.QUALITY },
            // 传输切换：WS ⇄ WebRTC（分享访客只能用进入时的方式，不提供切换）
            if (!t.isGuest) ToolItem(
                if (ws) "切到 WebRTC" else "切到 WS 模式",
                Icons.Outlined.Bolt, true,
            ) {
                close()
                vm.switchTransport(if (ws) com.webrtc_phone.android.rtc.StreamTransport.WEBRTC else com.webrtc_phone.android.rtc.StreamTransport.WEBSOCKET)
            } else null,
            // WS 模式无音频
            ToolItem(if (vm.audioOn) "声音开" else "已静音", if (vm.audioOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, !ws, active = !vm.audioOn) { vm.toggleAudio() },
            ToolItem("性能", Icons.Outlined.Insights, true, active = vm.settings.app.value.showStats) {
                vm.settings.updateApp { it.copy(showStats = !it.showStats) }
            },
            ToolItem("连接信息", Icons.Outlined.Info, true) { vm.panel = Panel.INFO },
            ToolItem("全屏模式", Icons.Outlined.Fullscreen, true, active = vm.fullscreen) { close(); vm.fullscreen = !vm.fullscreen },
            ToolItem("重新连接", Icons.Outlined.Refresh, true) { close(); vm.reconnect() },
        ),
    )
}
