package com.webrtc_phone.android.ui.stream

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.Tag
import com.webrtc_phone.android.data.repo.Snapshot
import com.webrtc_phone.android.ui.common.Pill
import com.webrtc_phone.android.ui.common.StatusDot
import com.webrtc_phone.android.ui.home.parseTagColor
import com.webrtc_phone.android.ui.theme.AppColors

/**
 * 投屏页侧边栏：设备列表，点击在线设备直接切换投屏目标。
 * 排序：当前设备置顶，其后在线（空闲优先），最后离线。
 */
@Composable
fun DeviceDrawer(vm: StreamViewModel) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    val loading by vm.devicesLoading.collectAsStateWithLifecycle()
    val tagState by vm.tags.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val current = vm.target.deviceId

    val q = query.trim().lowercase()
    val shown = devices
        .filter { q.isEmpty() || it.id.lowercase().contains(q) || it.info.title.lowercase().contains(q) }
        .sortedWith(
            compareByDescending<Device> { it.id == current }
                .thenByDescending { it.online }
                .thenBy { it.inUse }
                .thenBy { it.id }
        )

    ModalDrawerSheet(
        modifier = Modifier.width(320.dp).fillMaxHeight(),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("切换设备", style = MaterialTheme.typography.titleMedium)
                Text(
                    "在线 ${devices.count { it.online }} / 共 ${devices.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = vm::refreshDevices) { Icon(Icons.Filled.Refresh, "刷新") }
            IconButton(onClick = vm::closeDrawer) { Icon(Icons.Filled.Close, "关闭") }
        }
        TextField(
            value = query,
            onValueChange = { query = it },
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))

        if (shown.isEmpty() && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (devices.isEmpty()) "暂无设备" else "没有匹配的设备",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.id }) { d ->
                    DeviceDrawerItem(
                        device = d,
                        snapshot = snapshots[d.id],
                        deviceTags = tagState.deviceTags[d.id].orEmpty().mapNotNull { id -> tagState.tags.firstOrNull { it.id == id } },
                        current = d.id == current,
                        onClick = { vm.switchDevice(d) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceDrawerItem(device: Device, snapshot: Snapshot?, deviceTags: List<Tag>, current: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .then(if (current) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(enabled = device.online && !current, onClick = onClick)
            .alpha(if (device.online || current) 1f else 0.5f)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 缩略图（竖屏比例）
        Box(
            Modifier.width(44.dp).height(80.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111318)),
            contentAlignment = Alignment.Center
        ) {
            if (snapshot != null) {
                Image(
                    bitmap = remember(snapshot.version) { snapshot.bitmap.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    if (device.online) Icons.Outlined.PhoneAndroid else Icons.Outlined.CloudOff, null,
                    tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    when {
                        !device.online -> AppColors.Offline
                        device.inUse && !current -> AppColors.Busy
                        else -> AppColors.Online
                    }
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    device.id, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (device.info.title.isNotBlank()) {
                Text(
                    device.info.title + (if (device.info.androidVersion.isNotBlank()) " · Android ${device.info.androidVersion}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    current -> Pill("当前设备", AppColors.Brand, filled = true)
                    !device.online -> Pill("离线", AppColors.Offline)
                    device.inUse -> Pill("使用中 ${device.clientCount}", AppColors.Busy)
                    else -> Pill("空闲", AppColors.Online)
                }
                if (deviceTags.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    // 只放得下两个标签名，更多用圆点补充，避免挤占状态
                    deviceTags.take(2).forEach {
                        Pill(it.name, parseTagColor(it.color), filled = true)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (deviceTags.size > 2) deviceTags.drop(2).forEach {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(parseTagColor(it.color)))
                        Spacer(Modifier.width(3.dp))
                    }
                }
            }
        }
    }
}
