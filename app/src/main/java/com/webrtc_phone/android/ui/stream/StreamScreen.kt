package com.webrtc_phone.android.ui.stream

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.DeviceInfo
import com.webrtc_phone.android.rtc.ConnectionPhase
import com.webrtc_phone.android.rtc.channel.Keys
import com.webrtc_phone.android.ui.stream.terminal.TerminalPanel
import com.webrtc_phone.android.ui.stream.terminal.TerminalWebView
import com.webrtc_phone.android.ui.theme.AppColors
import org.webrtc.SurfaceViewRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamScreen(
    vm: StreamViewModel,
    renderer: SurfaceViewRenderer,
    onScreenshot: () -> Unit,
    onExit: () -> Unit,
) {
    val ctx = LocalContext.current
    val phase by vm.phase.collectAsStateWithLifecycle()
    val retry by vm.retry.collectAsStateWithLifecycle()
    val size by vm.videoSize.collectAsStateWithLifecycle()
    val firstFrame by vm.firstFrame.collectAsStateWithLifecycle()
    val app by vm.settings.app.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val terminal by vm.terminal.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val deviceInfo by (session?.deviceInfo ?: kotlinx.coroutines.flow.MutableStateFlow<DeviceInfo?>(null)).collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val t = vm.target
    val ratio = size?.let { it.first.toFloat() / it.second } ?: (9f / 19.5f)
    val connected = phase == ConnectionPhase.Connected || phase == ConnectionPhase.Interrupted

    // 终端 WebView：首次打开才创建，之后常驻
    val termView = remember(vm.terminalCreated) { if (vm.terminalCreated) TerminalWebView(ctx) else null }
    DisposableEffect(termView) { onDispose { termView?.release() } }
    LaunchedEffect(termView, terminal) {
        val v = termView ?: return@LaunchedEffect
        terminal?.output?.collect { v.write(it) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val showNav = app.showNavBar && !t.isCamera && !t.viewOnly
        val body: @Composable (Modifier) -> Unit = { m ->
            VideoArea(renderer, ratio, if (t.isCamera) vm.cameraZoom else 1f, m)
        }
        // 不含 IME insets：弹出键盘时画面保持不动，只有输入条/终端随键盘上移
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
            if (landscape) Row(Modifier.fillMaxSize()) {
                body(Modifier.weight(1f).fillMaxHeight())
                if (showNav) NavKeys(vm, vertical = true)
            } else Column(Modifier.fillMaxSize()) {
                body(Modifier.weight(1f).fillMaxWidth())
                if (t.isCamera) CameraBar(vm, Device(t.deviceId, deviceInfo ?: DeviceInfo(), true, "", null, 0, emptyList(), null, null), onScreenshot, onExit)
                if (showNav) NavKeys(vm, vertical = false)
            }
            if (landscape && t.isCamera) Box(Modifier.align(Alignment.BottomCenter)) {
                CameraBar(vm, Device(t.deviceId, deviceInfo ?: DeviceInfo(), true, "", null, 0, emptyList(), null, null), onScreenshot, onExit)
            }

            // 顶部提示
            Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (t.viewOnly) Banner("仅观看模式", Color.Gray)
                if (phase == ConnectionPhase.Interrupted) Banner("网络波动，正在恢复…", AppColors.Warning)
            }
            if (app.showStats && connected) StatsHud(stats, Modifier.align(Alignment.TopStart).padding(8.dp))

            if (vm.panel == Panel.TERMINAL && termView != null) {
                // 固定高度 + 外层 imePadding：键盘弹出时面板整体上移而不是被压缩
                // （agent 不支持 PTY resize，压缩后可见行数会远少于 shell 认为的行数）
                val panelHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
                Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
                    TerminalPanel(
                        termView, terminal, onClose = { vm.panel = Panel.NONE },
                        modifier = Modifier.fillMaxWidth().height(panelHeight)
                    )
                }
            }
            if (vm.keyboardMode) KeyboardBar(vm, Modifier.align(Alignment.BottomCenter).imePadding())
            if (!t.isCamera && vm.panel != Panel.TERMINAL && !vm.keyboardMode) FloatingBall { vm.panel = Panel.TOOLS }
        }

        when (val p = phase) {
            is ConnectionPhase.Failed -> FailedOverlay(p, onRetry = vm::reconnect, onExit = onExit)
            ConnectionPhase.Closed -> FailedOverlay(ConnectionPhase.Failed("会话已结束", retryable = true), onRetry = vm::reconnect, onExit = onExit)
            else -> if (!firstFrame) ConnectingOverlay(p, retry, t.deviceName, onExit)
        }
    }

    // 底部面板
    val sheetPanel = vm.panel.takeIf { it != Panel.NONE && it != Panel.TERMINAL }
    if (sheetPanel != null) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = sheetPanel == Panel.FILES)
        ModalBottomSheet(onDismissRequest = { vm.panel = Panel.NONE }, sheetState = state) {
            when (sheetPanel) {
                Panel.TOOLS -> ToolGrid(toolSections(vm, connected, onScreenshot, onExit))
                Panel.TEXT -> TextSendPanel(vm)
                Panel.FILES -> Box(Modifier.fillMaxHeight(0.85f)) { FilePanel(files, vm) { vm.panel = Panel.NONE } }
                Panel.QUALITY -> QualityPanel(vm) { vm.panel = Panel.NONE }
                Panel.INFO -> InfoPanel(vm)
                Panel.APPS -> AppsPanel(vm) { vm.panel = Panel.FILES }
                else -> Unit
            }
        }
    }
}

@Composable
private fun KeyboardBar(vm: StreamViewModel, modifier: Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            AndroidView(
                factory = { c ->
                    RemoteInputView(c).apply {
                        listener = object : RemoteInputView.Listener {
                            override fun onCommit(text: String) = vm.commitText(text)
                            override fun onKey(keycode: Int) = vm.press(keycode)
                        }
                        post {
                            requestFocus()
                            val imm = c.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                            imm?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                },
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
            )
            IconButton(onClick = { vm.press(Keys.ENTER) }) { Icon(Icons.AutoMirrored.Filled.KeyboardReturn, "回车") }
            IconButton(onClick = { vm.setKeyboard(false) }) { Icon(Icons.Filled.Check, "完成") }
        }
    }
}

@Suppress("unused")
@Composable
private fun Hint(text: String) = Text(text)
