package com.webrtc_phone.android.ui.stream

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.rtc.channel.LogcatFilter
import com.webrtc_phone.android.rtc.channel.LogcatLevel
import com.webrtc_phone.android.rtc.channel.LogcatLine
import com.webrtc_phone.android.rtc.channel.LogcatParser
import com.webrtc_phone.android.rtc.channel.TerminalChannel
import com.webrtc_phone.android.rtc.channel.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

private const val MAX_LOGCAT_LINES = 5_000
private val LOG_BUFFERS = listOf("main", "system", "crash", "events", "radio")
private val LEVELS = LogcatLevel.entries.filter { it != LogcatLevel.UNKNOWN }

/**
 * 复用当前设备的 adb-channel PTY 运行 logcat。进入面板时接管终端，退出时发送 Ctrl-C，
 * 避免后台 logcat 长期占用 shell；所有命令参数均来自固定白名单。
 */
private class LogcatCapture(
    private val channel: TerminalChannel,
    private val scope: CoroutineScope,
) {
    private val _lines = MutableStateFlow<List<LogcatLine>>(emptyList())
    val lines = _lines.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _paused = MutableStateFlow(false)
    val paused = _paused.asStateFlow()
    private val pending = ByteArrayOutputStream()
    private var collectJob: Job? = null
    private var commandJob: Job? = null

    fun start(buffers: Set<String>) {
        if (collectJob == null) {
            // logcat 与交互终端共用一个 PTY；首次接管时丢弃旧终端回放，避免混入历史命令输出。
            channel.clearHistory()
            collectJob = scope.launch {
                channel.output.collect { event ->
                    if (event.reset) pending.reset()
                    consume(event.bytes)
                }
            }
        }
        commandJob?.cancel()
        commandJob = scope.launch {
            _running.value = false
            var attempts = 0
            while (channel.pollState() != TerminalState.OPEN && attempts < 50) {
                delay(100)
                attempts++
            }
            if (channel.pollState() != TerminalState.OPEN) return@launch
            if (!channel.initialized && !channel.start(rows = 48, cols = 180)) return@launch
            delay(120)
            channel.write(byteArrayOf(0x03))
            delay(120)
            pending.reset()
            val safeBuffers = buffers.filter { it in LOG_BUFFERS }.ifEmpty { listOf("main") }
            val args = safeBuffers.joinToString(" ") { "-b $it" }
            _running.value = channel.write("logcat -v threadtime -T 200 $args 2>&1\r".toByteArray())
        }
    }

    fun togglePaused() { _paused.value = !_paused.value }

    fun clear() {
        _lines.value = emptyList()
        pending.reset()
    }

    fun stop() {
        commandJob?.cancel()
        if (_running.value) channel.write(byteArrayOf(0x03))
        _running.value = false
        collectJob?.cancel()
        collectJob = null
    }

    private fun consume(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        pending.write(bytes)
        val all = pending.toByteArray()
        var start = 0
        val parsed = ArrayList<LogcatLine>()
        for (i in all.indices) {
            if (all[i] == '\n'.code.toByte()) {
                if (!_paused.value) {
                    val text = all.copyOfRange(start, i).toString(Charsets.UTF_8).trimEnd('\r')
                    if (text.isNotBlank()) parsed += LogcatParser.parse(text)
                }
                start = i + 1
            }
        }
        pending.reset()
        if (start < all.size) pending.write(all, start, all.size - start)
        if (parsed.isNotEmpty()) {
            _lines.value = (_lines.value + parsed).takeLast(MAX_LOGCAT_LINES)
        }
    }
}

@Composable
fun LogcatPanel(
    vm: StreamViewModel,
    channel: TerminalChannel?,
    onClose: () -> Unit,
) {
    if (channel == null) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("日志通道正在连接…")
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onClose) { Text("关闭") }
        }
        return
    }

    val scope = rememberCoroutineScope()
    val capture = remember(channel) { LogcatCapture(channel, scope) }
    val lines by capture.lines.collectAsStateWithLifecycle()
    val running by capture.running.collectAsStateWithLifecycle()
    val paused by capture.paused.collectAsStateWithLifecycle()
    var buffers by remember(channel) { mutableStateOf(setOf("main", "system", "crash")) }
    var query by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var caseSensitive by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(LogcatLevel.VERBOSE) }
    var pidText by remember { mutableStateOf("") }
    var includeTags by remember { mutableStateOf("") }
    var excludeTags by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(true) }

    val filter = remember(query, regex, caseSensitive, level, pidText, includeTags, excludeTags) {
        LogcatFilter(
            query = query,
            regex = regex,
            caseSensitive = caseSensitive,
            minimumLevel = level,
            pid = pidText.toIntOrNull(),
            includedTags = includeTags.toTagSet(),
            excludedTags = excludeTags.toTagSet(),
        )
    }
    val visible = remember(lines, filter) { lines.filter(filter::matches) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(channel) { capture.start(buffers) }
    DisposableEffect(capture) { onDispose { capture.stop() } }
    LaunchedEffect(visible.size, autoScroll) {
        if (autoScroll && visible.isNotEmpty()) listState.scrollToItem(visible.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("设备日志 · ${vm.target.deviceName.ifBlank { vm.target.deviceId }}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${if (running) "● 采集中" else "○ 未启动"}${if (paused) " · 已暂停" else ""} · ${visible.size}/${lines.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (running) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = capture::clear) { Icon(Icons.Outlined.CleaningServices, "清空") }
            IconButton(onClick = capture::togglePaused) {
                Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, if (paused) "继续" else "暂停")
            }
            IconButton(onClick = {
                val text = visible.joinToString("\n") { it.raw }
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("logcat-${vm.target.deviceId}", text))
                vm.toast("已复制 ${visible.size} 条日志")
            }) { Icon(Icons.Outlined.ContentCopy, "复制筛选结果") }
            if (vm.canSwitchDevices) {
                TextButton(onClick = vm::openDrawer) { Text("换设备") }
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            singleLine = true,
            label = { Text(if (regex) "内容正则表达式" else "搜索日志内容") },
            trailingIcon = {
                IconButton(onClick = { advanced = !advanced }) { Icon(Icons.Outlined.FilterAlt, "高级筛选") }
            },
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(regex, { regex = !regex }, { Text("正则") })
            FilterChip(caseSensitive, { caseSensitive = !caseSensitive }, { Text("区分大小写") })
            FilterChip(autoScroll, { autoScroll = !autoScroll }, { Text("自动滚动") })
            LEVELS.forEach { item ->
                FilterChip(level == item, { level = item }, { Text("${item.code}+") })
            }
        }

        if (advanced) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pidText, { pidText = it.filter(Char::isDigit) }, Modifier.weight(0.7f), singleLine = true, label = { Text("PID") })
                OutlinedTextField(includeTags, { includeTags = it }, Modifier.weight(1.3f), singleLine = true, label = { Text("包含标签（逗号分隔）") })
            }
            OutlinedTextField(
                excludeTags, { excludeTags = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                singleLine = true, label = { Text("排除标签（逗号分隔）") },
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("缓冲区", style = MaterialTheme.typography.labelMedium)
                LOG_BUFFERS.forEach { name ->
                    FilterChip(name in buffers, {
                        val next = if (name in buffers) buffers - name else buffers + name
                        if (next.isNotEmpty()) {
                            buffers = next
                            capture.clear()
                            capture.start(next)
                        }
                    }, { Text(name) })
                }
                Button(onClick = { capture.clear(); capture.start(buffers) }) { Text("重新采集") }
            }
        }

        Surface(
            color = Color(0xFF0B1020),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxHeight().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 4.dp),
            ) {
                items(visible.size, key = { it }) { index -> LogLineRow(visible[index]) }
            }
        }
    }
}

@Composable
private fun LogLineRow(line: LogcatLine) {
    val color = when (line.level) {
        LogcatLevel.VERBOSE -> Color(0xFF94A3B8)
        LogcatLevel.DEBUG -> Color(0xFF60A5FA)
        LogcatLevel.INFO -> Color(0xFF86EFAC)
        LogcatLevel.WARN -> Color(0xFFFACC15)
        LogcatLevel.ERROR, LogcatLevel.FATAL -> Color(0xFFF87171)
        LogcatLevel.UNKNOWN -> Color(0xFFCBD5E1)
    }
    Text(
        line.raw,
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 12,
        overflow = TextOverflow.Clip,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp).width(1600.dp),
    )
}

private fun String.toTagSet(): Set<String> = split(',', '，', ';', ' ')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .toSet()
