package com.webrtc_phone.android.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.core.util.Formatters
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.Tag
import com.webrtc_phone.android.data.realtime.SocketState
import com.webrtc_phone.android.data.repo.Snapshot
import com.webrtc_phone.android.ui.common.EmptyState
import com.webrtc_phone.android.ui.common.Pill
import com.webrtc_phone.android.ui.common.StatusDot
import com.webrtc_phone.android.ui.theme.AppColors

fun parseTagColor(hex: String, fallback: Color = AppColors.Brand): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(fallback)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesTab(vm: HomeViewModel, onOpen: (Device) -> Unit, onMore: (Device) -> Unit) {
    val list by vm.filtered.collectAsStateWithLifecycle()
    val all by vm.devices.collectAsStateWithLifecycle()
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val socket by vm.socketState.collectAsStateWithLifecycle()
    val app by vm.appSettings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        SearchBar(
            query = filter.query,
            onQuery = { q -> vm.setFilter { it.copy(query = q) } },
            columns = app.gridColumns,
            onColumns = vm::setGridColumns,
        )
        FilterRow(vm, filter, tags.tags, all)
        if (socket == SocketState.RECONNECTING) {
            Surface(color = AppColors.Warning.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WifiOff, null, tint = AppColors.Warning, modifier = Modifier.size(16.dp))
                    Text("  实时连接中断，正在重连…", style = MaterialTheme.typography.bodySmall, color = AppColors.Warning)
                }
            }
        }

        PullToRefreshBox(isRefreshing = loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
            when {
                list.isEmpty() && error != null && all.isEmpty() -> EmptyState(
                    Icons.Outlined.CloudOff, "无法加载设备", error?.message,
                    action = { Button(onClick = { vm.refresh() }) { Text("重试") } }
                )
                list.isEmpty() && !loading -> EmptyState(
                    Icons.Outlined.PhoneAndroid,
                    if (all.isEmpty()) "暂无设备" else "没有符合条件的设备",
                    if (all.isEmpty()) "请联系管理员为你分配设备，或在设备上部署 Agent" else "调整搜索或筛选条件试试",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(app.gridColumns),
                    contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.id }) { d ->
                        DeviceCard(
                            device = d,
                            snapshot = snapshots[d.id],
                            tags = tags.deviceTags[d.id].orEmpty().mapNotNull { id -> tags.tags.firstOrNull { it.id == id } },
                            compact = app.gridColumns >= 3,
                            onClick = { onOpen(d) },
                            onLongClick = { onMore(d) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, columns: Int, onColumns: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("搜索设备 ID / 型号") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.weight(1f).height(52.dp),
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.GridView, "布局") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                (1..4).forEach { n ->
                    DropdownMenuItem(
                        text = { Text((if (n == columns) "● " else "　") + "每行 $n 台") },
                        onClick = { menu = false; onColumns(n) })
                }
            }
        }
    }
}

@Composable
private fun FilterRow(vm: HomeViewModel, filter: DeviceFilter, tags: List<Tag>, all: List<Device>) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusFilter.entries.forEach { sf ->
            val count = when (sf) {
                StatusFilter.ALL -> all.size
                StatusFilter.ONLINE -> all.count { it.online }
                StatusFilter.IN_USE -> all.count { it.online && it.inUse }
                StatusFilter.OFFLINE -> all.count { !it.online }
            }
            FilterChip(
                selected = filter.status == sf,
                onClick = { vm.setFilter { it.copy(status = sf) } },
                label = { Text("${sf.label} $count") },
            )
        }
        tags.forEach { t ->
            val sel = t.id in filter.tagIds
            FilterChip(
                selected = sel,
                onClick = { vm.setFilter { f -> f.copy(tagIds = if (sel) f.tagIds - t.id else f.tagIds + t.id) } },
                label = { Text(t.name) },
                leadingIcon = { StatusDot(parseTagColor(t.color)) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceCard(
    device: Device,
    snapshot: Snapshot?,
    tags: List<Tag>,
    compact: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val ratio = device.info.primaryDisplay?.let { it.width.toFloat() / it.height }?.coerceIn(0.4f, 2.2f) ?: (9f / 19.5f)
    Card(
        modifier = modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
        ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(MaterialTheme.shapes.medium).background(Color(0xFF111318))) {
            if (snapshot != null) {
                Image(
                    bitmap = remember(snapshot.version) { snapshot.bitmap.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (!device.online || device.screenOff) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                    alpha = if (device.online) 1f else 0.45f,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    if (device.online) Icons.Outlined.PhoneAndroid else Icons.Outlined.CloudOff, null,
                    tint = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.align(Alignment.Center).size(if (compact) 28.dp else 40.dp)
                )
            }
            if (device.online && device.screenOff) {
                Icon(
                    Icons.Outlined.DarkMode, "息屏", tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.Center).size(28.dp)
                )
            }
            // 顶部状态
            Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill(
                    when {
                        !device.online -> "离线"
                        device.inUse -> if (compact) "使用中" else "使用中 ${device.clientCount}"
                        else -> "在线"
                    },
                    when {
                        !device.online -> AppColors.Offline
                        device.inUse -> AppColors.Busy
                        else -> AppColors.Online
                    },
                    filled = true,
                )
                device.myLeaseRemainingSeconds?.let { rem ->
                    if (!compact) Pill(Formatters.duration(rem), if (rem < 86400) AppColors.Danger else Color.White, filled = rem < 86400)
                }
            }
            // 底部信息
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                if (tags.isNotEmpty() && !compact) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.take(3).forEach { Pill(it.name, parseTagColor(it.color), filled = true) }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    device.id, color = Color.White,
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (!compact && device.info.title.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            device.info.title + (if (device.info.androidVersion.isNotBlank()) " · Android ${device.info.androidVersion}" else ""),
                            color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                        )
                        if (device.leaseHolder != null) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Outlined.Timer, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                            Text(device.leaseHolder, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
