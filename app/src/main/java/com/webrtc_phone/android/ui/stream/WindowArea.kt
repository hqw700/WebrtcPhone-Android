package com.webrtc_phone.android.ui.stream

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.rtc.RtcSession
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
fun WindowArea(vm: StreamViewModel, session: RtcSession, modifier: Modifier, video: @Composable () -> Unit) {
    val ready by session.inputReady.collectAsStateWithLifecycle()
    val apps by session.windowApps.collectAsStateWithLifecycle()
    val icons by session.windowIcons.collectAsStateWithLifecycle()
    val notice by session.windowNotice.collectAsStateWithLifecycle()
    var query by remember(session) { mutableStateOf("") }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(session) { vm.showWindowApps = true }
    LaunchedEffect(session, size, ready) {
        if (ready && size.width > 0 && size.height > 0) {
            delay(220)
            val scale = minOf(1.0, 4096.0 / maxOf(size.width, size.height))
            session.windowCommand(JSONObject().put("width", (size.width * scale).roundToInt().coerceAtLeast(128))
                .put("height", (size.height * scale).roundToInt().coerceAtLeast(128)))
        }
    }
    Column(modifier) {
        TextButton(onClick = { vm.showWindowApps = !vm.showWindowApps }) {
            Text(if (vm.showWindowApps) "查看副屏" else "应用列表")
        }
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.error)
        Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
            video()
            if (vm.showWindowApps) Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索应用名称或包名") })
                val shown = remember(apps, query) {
                    val term = query.trim()
                    apps.filter { (pkg, name) -> pkg.contains(term, true) || name.contains(term, true) }.entries.toList()
                }
                if (!ready) Text("正在连接副屏…")
                else if (shown.isEmpty()) Text("没有匹配的应用")
                LazyVerticalGrid(columns = GridCells.Adaptive(100.dp)) {
                    items(shown, key = { it.key }) { (pkg, name) ->
                        TextButton(enabled = ready, onClick = {
                            if (session.windowCommand(JSONObject().put("package", pkg))) {
                                vm.showWindowApps = false
                                query = ""
                            }
                        }) {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                val png = icons[pkg]
                                val image = remember(png) { png?.let {
                                    runCatching {
                                        val data = Base64.decode(it, Base64.DEFAULT)
                                        BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
                                    }.getOrNull()
                                } }
                                if (image != null) Image(image, null, Modifier.size(40.dp))
                                Text(name)
                            }
                        }
                    }
                }
            }
        }
    }
}
