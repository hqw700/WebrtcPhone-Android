package com.webrtc_phone.android.ui.stream

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.core.util.Formatters
import com.webrtc_phone.android.rtc.StreamStats
import com.webrtc_phone.android.ui.manage.QuickTextDialog
import com.webrtc_phone.android.ui.settings.StreamQualityEditor
import kotlinx.coroutines.launch

data class ToolItem(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val active: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** 工具面板：宫格按钮 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolGrid(sections: List<Pair<String, List<ToolItem>>>) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        sections.forEach { (title, items) ->
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
            FlowRow(Modifier.padding(horizontal = 8.dp), maxItemsInEachRow = 5) {
                items.forEach { t -> ToolButton(t, Modifier.weight(1f)) }
                repeat((5 - items.size % 5) % 5) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ToolButton(t: ToolItem, modifier: Modifier) {
    val alpha = if (t.enabled) 1f else 0.35f
    Column(
        modifier.clip16().clickable(enabled = t.enabled, onClick = t.onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(46.dp).background(
                if (t.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(14.dp)
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                t.icon, t.label,
                tint = (if (t.active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface).copy(alpha = alpha)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(t.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    }
}

private fun Modifier.clip16() = this.clip(RoundedCornerShape(16.dp))

/** 文本下发：自由输入 + 快捷文本模板 */
@Composable
fun TextSendPanel(vm: StreamViewModel) {
    var text by remember { mutableStateOf("") }
    var enter by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val repo = vm.quickTexts
    val items by (repo?.items ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(repo) { runCatching { repo?.load() } }

    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        OutlinedTextField(
            text, { text = it }, label = { Text("要发送的文本") },
            minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("发送后回车", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Switch(enter, { enter = it })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.syncLocalClipboard(vm.appContext(), force = true) }) { Text("发送剪贴板") }
            Button(onClick = { vm.sendText(text, enter); text = "" }, enabled = text.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("发送")
            }
        }
        if (repo != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("快捷文本", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { creating = true }) { Text("新建") }
            }
            if (items.isEmpty()) Text("暂无模板", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.height(220.dp)) {
                items(items, key = { it.id }) { q ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.sendQuickText(q) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ShortText, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(q.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(q.content, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (q.autoEnter) Text("↵", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    if (creating && repo != null) QuickTextDialog(null, onDismiss = { creating = false }) { t, c, a ->
        creating = false
        scope.launch { runCatching { repo.upsert(t, c, a) }.onFailure { vm.toast(it.message ?: "保存失败") } }
    }
}

@Composable
fun QualityPanel(vm: StreamViewModel, onApplied: () -> Unit) {
    val s by vm.settings.stream.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(s) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        StreamQualityEditor(draft, null, onChange = { t -> draft = t(draft) })
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { draft = s }) { Text("还原") }
            Spacer(Modifier.width(8.dp))
            Button(enabled = draft != s, onClick = { vm.applyQuality { draft }; onApplied() }) { Text("应用并重连") }
        }
    }
}

@Composable
fun InfoPanel(vm: StreamViewModel) {
    val st by vm.stats.collectAsStateWithLifecycle()
    val size by vm.videoSize.collectAsStateWithLifecycle()
    val t = vm.target
    val rows = listOf(
        "设备" to "${t.deviceName} (${t.deviceId})",
        "服务器" to t.endpoint.display,
        "身份" to if (t.isGuest) "卡密访客 · ${t.accessMode.label}" else "登录用户",
        "画面" to (size?.let { "${it.first} × ${it.second}" } ?: "-"),
        "编码" to "${st.codec.ifBlank { "-" }} ${st.decoder}".trim(),
        "网络" to listOf(st.networkLabel, st.protocol.uppercase()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "-" },
        "延迟" to if (st.latencyMs > 0) "${st.latencyMs} ms (RTT ${st.rttMs} ms)" else "-",
        "帧率" to "${st.fps} fps",
        "码率" to Formatters.bitrate(st.bitrateBps),
        "丢包" to "%.1f%%".format(st.packetLossPct),
        "抖动缓冲" to "${st.jitterBufferMs} ms · 解码 ${st.decodeMs} ms",
        "丢帧" to st.framesDropped.toString(),
    )
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(k, Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** 应用安装：本机 APK 上传并安装已实现；“本机已安装应用”预留 */
@Composable
fun AppsPanel(vm: StreamViewModel, onOpenFiles: () -> Unit) {
    val ctx = LocalContext.current
    val files by vm.files.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val c = files ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val p = queryLocal(ctx, uri)
        val name = if (p.name.endsWith(".apk", true)) p.name else p.name + ".apk"
        c.upload(uri, name, p.size, "/data/local/tmp/$name", installAfter = true)
        vm.toast("正在上传 $name，完成后自动安装")
        onOpenFiles()
    }
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        AppAction(Icons.Outlined.Android, "从本机选择 APK 安装", "上传到云手机并自动安装") {
            if (!vm.target.allowFileTx) vm.toast("该分享未开放文件传输") else launcher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        }
        AppAction(Icons.Outlined.Folder, "从云手机文件安装", "浏览云手机存储，点选 APK 安装", onClick = onOpenFiles)
        AppAction(Icons.Outlined.PhoneAndroid, "从本机已安装应用选择", "即将支持：直接提取本机应用推送到云手机", enabled = false) {}
        AppAction(Icons.Outlined.Apps, "云手机应用管理", "即将支持：查看/卸载/清除数据", enabled = false) {}
    }
}

@Composable
private fun AppAction(icon: ImageVector, title: String, sub: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f))
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 角落性能浮层 */
@Composable
fun StatsHud(st: StreamStats, modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(
            buildString {
                append(if (st.latencyMs > 0) "${st.latencyMs}ms" else "--")
                append("  ${st.fps}fps  ${Formatters.bitrate(st.bitrateBps)}")
                if (st.packetLossPct >= 0.1f) append("  丢包%.1f%%".format(st.packetLossPct))
                if (st.networkLabel.isNotBlank()) append("\n${st.networkLabel}")
                if (st.width > 0) append("  ${st.width}×${st.height}")
            },
            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
