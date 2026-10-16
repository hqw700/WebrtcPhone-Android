package com.webrtc_phone.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.webrtc_phone.android.core.util.Formatters
import com.webrtc_phone.android.data.model.CameraLens
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.Tag
import com.webrtc_phone.android.rtc.CameraParams
import com.webrtc_phone.android.ui.common.ConfirmDialog
import com.webrtc_phone.android.ui.common.SettingRow
import com.webrtc_phone.android.ui.common.StatusDot
import com.webrtc_phone.android.ui.theme.AppColors

/** 设备长按菜单 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceActionSheet(
    device: Device,
    isAdmin: Boolean,
    canShare: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCamera: () -> Unit,
    onShare: () -> Unit,
    onTags: () -> Unit,
    onDelete: () -> Unit,
) {
    var info by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (device.online) AppColors.Online else AppColors.Offline, size = 10)
                Spacer(Modifier.padding(4.dp))
                Column {
                    Text(device.id, style = MaterialTheme.typography.titleMedium)
                    if (device.info.title.isNotBlank()) Text(
                        device.info.title, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingRow("进入投屏", icon = Icons.Outlined.CastConnected, enabled = device.online, onClick = { onDismiss(); onOpen() })
            SettingRow(
                "摄像头监控", "使用云手机摄像头作为画面，含环境音",
                icon = Icons.Outlined.Videocam, enabled = device.online,
                onClick = { onDismiss(); onCamera() }
            )
            if (canShare) SettingRow("分享 / 生成卡密", icon = Icons.Outlined.Share, onClick = { onDismiss(); onShare() })
            if (isAdmin) SettingRow("设置标签", icon = Icons.Outlined.Label, onClick = { onDismiss(); onTags() })
            SettingRow("设备信息", icon = Icons.Outlined.Info, onClick = { info = !info })
            if (info) DeviceInfoBlock(device)
            if (isAdmin && !device.online) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SettingRow("删除设备档案", icon = Icons.Outlined.Delete, tint = AppColors.Danger, onClick = { confirmDelete = true })
            }
        }
    }
    if (confirmDelete) ConfirmDialog(
        "删除设备", "将删除离线设备 ${device.id} 的档案，设备重新上线后会自动重新注册。",
        confirmText = "删除", destructive = true,
        onConfirm = { onDismiss(); onDelete() }, onDismiss = { confirmDelete = false }
    )
}

@Composable
private fun DeviceInfoBlock(d: Device) {
    val i = d.info
    val rows = listOfNotNull(
        "品牌型号" to i.title.ifBlank { "-" },
        "系统" to (if (i.androidVersion.isNotBlank()) "Android ${i.androidVersion} (SDK ${i.sdkInt})" else "-"),
        i.primaryDisplay?.let { "分辨率" to "${it.width} × ${it.height}" },
        if (i.cpuAbi.isNotBlank()) "架构" to i.cpuAbi else null,
        if (i.memTotalMb > 0) "内存" to Formatters.bytes(i.memTotalMb * 1024 * 1024) else null,
        if (i.agentVersion.isNotBlank()) "Agent" to i.agentVersion else null,
        "摄像头" to (if (i.cameras.isEmpty()) "未检测到" else i.cameras.joinToString { it.name.ifBlank { it.id } }),
        d.lastSeen?.let { "最后在线" to Formatters.dateTime(it) },
        d.myLeaseRemainingSeconds?.let { "租约剩余" to Formatters.duration(it) },
        d.leaseHolder?.let { "租约用户" to it },
        if (d.clients.isNotEmpty()) "接入者" to d.clients.joinToString { c -> if (c.name.isBlank()) "${c.count} 人" else c.name } else null,
    )
    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(k, Modifier.weight(0.3f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

data class LensOption(val label: String, val lens: CameraLens)

fun lensOptions(d: Device): List<LensOption> {
    val detected = d.info.cameras
    if (detected.isNotEmpty()) return detected.map { c ->
        val label = when {
            c.facing == "front" -> "前置"
            c.facing == "external" -> "外接"
            c.zoom < 0.9 -> "广角 ${"%.1f".format(c.zoom)}x"
            c.zoom > 1.5 -> "长焦 ${"%.1f".format(c.zoom)}x"
            else -> "主摄 1x"
        }
        LensOption(label, c)
    }
    // 未上报镜头时的兜底：多摄统一走 Camera 0 + 硬件 zoom（不传不存在的镜头 id）
    return listOf(
        LensOption("主摄 1x", CameraLens("0", "back", "main", "主摄", 1.0)),
        LensOption("广角 0.5x", CameraLens("0", "back", "ultra_wide", "广角", 0.5)),
        LensOption("长焦 2x", CameraLens("0", "back", "telephoto", "长焦", 2.0)),
        LensOption("前置", CameraLens("1", "front", "front", "前置", 1.0)),
    )
}

val CameraResolutions = listOf("640x480" to "480P", "1280x720" to "720P", "1920x1080" to "1080P", "3840x2160" to "4K")

/** 摄像头监控参数选择 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CameraPickerSheet(device: Device, onDismiss: () -> Unit, onStart: (CameraParams) -> Unit) {
    val lenses = remember(device) { lensOptions(device) }
    var lens by remember { mutableStateOf(lenses.first()) }
    var res by remember { mutableStateOf(CameraResolutions[2]) }
    var fps by remember { mutableStateOf(30) }
    var mic by remember { mutableStateOf(true) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text("摄像头监控 · ${device.id}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text("镜头", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lenses.forEach { o -> FilterChip(selected = lens == o, onClick = { lens = o }, label = { Text(o.label) }) }
            }
            Spacer(Modifier.height(12.dp))
            Text("分辨率", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraResolutions.forEach { r -> FilterChip(selected = res == r, onClick = { res = r }, label = { Text(r.second) }) }
            }
            Spacer(Modifier.height(12.dp))
            Text("帧率", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { f -> FilterChip(selected = fps == f, onClick = { fps = f }, label = { Text("$f fps") }) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("采集环境音（设备麦克风）", Modifier.weight(1f))
                Switch(checked = mic, onCheckedChange = { mic = it })
            }
            Button(
                onClick = {
                    onDismiss()
                    onStart(CameraParams(lens.lens.facing, lens.lens.id, res.first, fps, lens.lens.zoom, mic))
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("开始监控") }
        }
    }
}

/** 设备标签多选 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DeviceTagSheet(
    deviceId: String,
    tags: List<Tag>,
    selected: List<String>,
    onDismiss: () -> Unit,
    onManageTags: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var sel by remember { mutableStateOf(selected.toSet()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text("设置标签 · $deviceId", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (tags.isEmpty()) Text("还没有标签", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { t ->
                    val on = t.id in sel
                    FilterChip(
                        selected = on,
                        onClick = { sel = if (on) sel - t.id else sel + t.id },
                        label = { Text(t.name) },
                        leadingIcon = { StatusDot(parseTagColor(t.color)) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDismiss(); onManageTags() }) { Text("管理标签") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onDismiss(); onSave(tags.map { it.id }.filter { it in sel }) }) { Text("保存") }
            }
        }
    }
}
