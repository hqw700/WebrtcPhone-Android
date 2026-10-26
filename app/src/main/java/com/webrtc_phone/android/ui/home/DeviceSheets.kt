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
import androidx.compose.material.icons.outlined.Bolt
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
import com.webrtc_phone.android.ui.common.Pill
import com.webrtc_phone.android.ui.theme.AppColors

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.font.FontWeight
import com.webrtc_phone.android.data.model.AdminLease
import com.webrtc_phone.android.data.model.AdminUser
import com.webrtc_phone.android.ui.common.Durations

/** 设备长按菜单 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceActionSheet(
    device: Device,
    isAdmin: Boolean,
    canShare: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onOpenWs: () -> Unit,
    onCamera: () -> Unit,
    onShare: () -> Unit,
    onTags: () -> Unit,
    onLease: () -> Unit = {},
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
                "WS 模式投屏", "WebSocket 中转，免 P2P 穿透（视频+直控）",
                icon = Icons.Outlined.Bolt, enabled = device.online,
                onClick = { onDismiss(); onOpenWs() }
            )
            SettingRow(
                "摄像头监控", "使用云手机摄像头作为画面，含环境音",
                icon = Icons.Outlined.Videocam, enabled = device.online,
                onClick = { onDismiss(); onCamera() }
            )
            if (canShare) SettingRow("分享 / 生成卡密", icon = Icons.Outlined.Share, onClick = { onDismiss(); onShare() })
            if (isAdmin) {
                val leaseSub = if (device.leaseHolder != null) {
                    val rem = device.myLeaseRemainingSeconds?.let { "，剩 " + Formatters.duration(it) } ?: ""
                    "已租给 ${device.leaseHolder}$rem"
                } else {
                    "当前未出租，点击为用户开租"
                }
                SettingRow("设备开租 / 租约管理", leaseSub, icon = Icons.Outlined.Key, onClick = { onDismiss(); onLease() })
                SettingRow("设置标签", icon = Icons.Outlined.Label, onClick = { onDismiss(); onTags() })
            }
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

/** 设备专属开租与租约管理 Sheet */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DeviceLeaseSheet(
    device: Device,
    onDismiss: () -> Unit,
    fetchLease: suspend () -> AdminLease?,
    fetchUsers: suspend () -> List<AdminUser>,
    onCreateLease: (username: String, durationSeconds: Long, note: String) -> Unit,
    onExtendLease: (leaseId: String, addedSeconds: Long) -> Unit,
    onRevokeLease: (leaseId: String) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var lease by remember { mutableStateOf<AdminLease?>(null) }
    var users by remember { mutableStateOf<List<AdminUser>>(emptyList()) }

    var selectedUsername by remember { mutableStateOf("") }
    var selectedDuration by remember { mutableStateOf(Durations.lease[4]) }
    var isCustomDuration by remember { mutableStateOf(false) }
    var customDays by remember { mutableStateOf("1") }
    var note by remember { mutableStateOf("") }
    var showExtendDialog by remember { mutableStateOf(false) }
    var showRevokeConfirm by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(device.id) {
        loading = true
        lease = fetchLease()
        users = fetchUsers()
        if (users.isNotEmpty()) selectedUsername = users.first().username
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (device.online) AppColors.Online else AppColors.Offline, size = 10)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("设备开租与租约管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("设备 ID: ${device.id}" + (if (device.info.title.isNotBlank()) " (${device.info.title})" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(14.dp))

            if (loading) {
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    Text("正在加载租约与用户信息…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val l = lease
                if (l != null && l.active) {
                    // 已有活跃租约
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("当前租户：", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(l.username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Pill(
                                    if (l.isPermanent) "永久有效" else "剩余 ${Formatters.duration(l.remainingSeconds)}",
                                    if (l.isExpiringSoon()) AppColors.Warning else AppColors.Online,
                                    filled = true
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            if (l.note.isNotBlank()) {
                                Text("备注：${l.note}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "开通时间：" + (l.createdAt?.let { Formatters.dateTime(it) } ?: "-"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(Modifier.height(8.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Button(
                                    onClick = { showRevokeConfirm = true },
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = AppColors.Danger.copy(alpha = 0.15f), contentColor = AppColors.Danger),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text("提前收回")
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { showExtendDialog = true },
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Icon(Icons.Outlined.Update, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("租约展期")
                                }
                            }
                        }
                    }
                } else {
                    // 设备未出租，提供开租表单
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("为该设备开通租约", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))

                            Text("选择租户账号：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            var userMenu by remember { mutableStateOf(false) }
                            Box {
                                androidx.compose.material3.OutlinedButton(
                                    onClick = { userMenu = true },
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(if (selectedUsername.isNotBlank()) selectedUsername else "请选择用户")
                                        Text("▾")
                                    }
                                }
                                DropdownMenu(expanded = userMenu, onDismissRequest = { userMenu = false }) {
                                    users.forEach { u ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    StatusDot(if (u.online) AppColors.Online else AppColors.Offline, size = 8)
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(u.username + if (u.note.isNotBlank()) " (${u.note})" else "")
                                                }
                                            },
                                            onClick = {
                                                selectedUsername = u.username
                                                userMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))
                            Text("租用时长：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Durations.lease.forEach { opt ->
                                    FilterChip(
                                        selected = !isCustomDuration && selectedDuration == opt,
                                        onClick = { isCustomDuration = false; selectedDuration = opt },
                                        label = { Text(opt.label) },
                                        shape = MaterialTheme.shapes.small
                                    )
                                }
                                FilterChip(
                                    selected = isCustomDuration,
                                    onClick = { isCustomDuration = true },
                                    label = { Text("自定义天数") },
                                    shape = MaterialTheme.shapes.small
                                )
                            }
                            if (isCustomDuration) {
                                Spacer(Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = customDays,
                                    onValueChange = { customDays = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("租用天数") },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = note,
                                onValueChange = { note = it.take(80) },
                                label = { Text("租约备注（如订单号 / 用途）") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(14.dp))
                            Button(
                                enabled = selectedUsername.isNotBlank(),
                                onClick = {
                                    val durSec = if (isCustomDuration) (customDays.toLongOrNull() ?: 1L) * 86400L else selectedDuration.seconds
                                    onCreateLease(selectedUsername, durSec, note.trim())
                                    onDismiss()
                                },
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("立即开租")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showRevokeConfirm && lease != null) {
        ConfirmDialog(
            title = "收回设备租约",
            message = "确定收回用户 ${lease!!.username} 对设备 ${device.id} 的租约？其连接将被立即切断。",
            confirmText = "确认收回",
            destructive = true,
            onConfirm = {
                showRevokeConfirm = false
                onRevokeLease(lease!!.leaseId)
                onDismiss()
            },
            onDismiss = { showRevokeConfirm = false }
        )
    }

    if (showExtendDialog && lease != null) {
        var extOption by remember { mutableStateOf(Durations.leaseExtend[3]) } // 默认 +1 天
        AlertDialog(
            onDismissRequest = { showExtendDialog = false },
            title = { Text("租约展期") },
            text = {
                Column {
                    Text("选择为设备 ${device.id} 延长的时间：", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Durations.leaseExtend.forEach { opt ->
                            FilterChip(
                                selected = extOption == opt,
                                onClick = { extOption = opt },
                                label = { Text(opt.label) },
                                shape = MaterialTheme.shapes.small
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExtendDialog = false
                        onExtendLease(lease!!.leaseId, extOption.seconds)
                        onDismiss()
                    }
                ) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showExtendDialog = false }) { Text("取消") } }
        )
    }
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
        LensOption("长焦 5x", CameraLens("0", "back", "telephoto", "长焦", 5.0)),
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
