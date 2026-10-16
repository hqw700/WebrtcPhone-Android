package com.webrtc_phone.android.ui.stream.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.rtc.channel.TerminalChannel
import com.webrtc_phone.android.rtc.channel.TerminalState

private val EXTRA_KEYS = listOf(
    "Esc" to byteArrayOf(0x1b), "Tab" to byteArrayOf(0x09),
    "^C" to byteArrayOf(0x03), "^D" to byteArrayOf(0x04), "^Z" to byteArrayOf(0x1a), "^L" to byteArrayOf(0x0c),
    "↑" to "\u001b[A".toByteArray(), "↓" to "\u001b[B".toByteArray(),
    "←" to "\u001b[D".toByteArray(), "→" to "\u001b[C".toByteArray(),
    "|" to "|".toByteArray(), "/" to "/".toByteArray(), "-" to "-".toByteArray(),
)

/** 半屏终端面板（关闭只隐藏，shell 会话随连接保留） */
@Composable
fun TerminalPanel(view: TerminalWebView, channel: TerminalChannel?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by (channel?.state ?: kotlinx.coroutines.flow.MutableStateFlow(TerminalState.CONNECTING)).collectAsStateWithLifecycle()
    LaunchedEffect(channel, state) { view.bind(channel); view.maybeStart() }

    Column(modifier.background(Color(0xF0141823))) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("终端", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text(
                when (state) {
                    TerminalState.OPEN -> "  ● 已连接"
                    TerminalState.CONNECTING -> "  ○ 连接中"
                    TerminalState.CLOSED -> "  ○ 已断开（重新连接设备可恢复）"
                },
                color = if (state == TerminalState.OPEN) Color(0xFF22C55E) else Color(0xFFF59E0B),
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { view.clear() }) { Icon(Icons.Outlined.CleaningServices, "清屏", tint = Color.White) }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "收起", tint = Color.White) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { (view.parent as? android.view.ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxSize())
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            EXTRA_KEYS.forEach { (label, bytes) ->
                AssistChip(
                    onClick = { channel?.write(bytes) },
                    label = { Text(label) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = Color.White),
                )
            }
        }
    }
}
