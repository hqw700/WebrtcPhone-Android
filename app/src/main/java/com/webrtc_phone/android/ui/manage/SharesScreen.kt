package com.webrtc_phone.android.ui.manage

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.core.util.Formatters
import com.webrtc_phone.android.data.model.AccessMode
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.Share
import com.webrtc_phone.android.data.model.ShareCreateRequest
import com.webrtc_phone.android.data.model.ShareCreated
import com.webrtc_phone.android.ui.common.ConfirmDialog
import com.webrtc_phone.android.ui.common.Durations
import com.webrtc_phone.android.ui.common.EmptyState
import com.webrtc_phone.android.ui.common.PasswordField
import com.webrtc_phone.android.ui.common.Pill
import com.webrtc_phone.android.ui.settings.SubScreen
import com.webrtc_phone.android.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharesScreen(
    vm: SharesViewModel,
    devices: List<Device>,
    openCreate: Boolean,
    onBack: () -> Unit,
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val created by vm.created.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(openCreate) }
    var revoke by remember { mutableStateOf<Share?>(null) }
    var extend by remember { mutableStateOf<Share?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    SubScreen(
        title = if (vm.deviceFilter.isNullOrBlank()) "分享与卡密" else "分享 · ${vm.deviceFilter}",
        onBack = onBack,
        floating = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("新建分享") })
        },
        snackbar = { SnackbarHost(snackbar) },
    ) { pad ->
        PullToRefreshBox(st.loading, onRefresh = { vm.load() }, modifier = Modifier.padding(pad)) {
            if (st.items.isEmpty() && !st.loading) {
                EmptyState(Icons.Outlined.Share, st.error ?: "暂无有效分享", "创建分享后，访客可凭卡密在 App 或网页免登录访问设备")
            } else LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(st.items, key = { it.token }) { s ->
                    ShareCard(s, vm, onRevoke = { revoke = s }, onExtend = { extend = s })
                }
            }
        }
    }

    if (showCreate) ShareCreateDialog(
        devices = devices,
        presetDevice = vm.deviceFilter,
        onDismiss = { showCreate = false },
        onCreate = { showCreate = false; vm.create(it) },
    )
    created?.let { ShareResultDialog(it, vm.linkFor(it.token), onDismiss = vm::dismissCreated) }
    revoke?.let { s ->
        ConfirmDialog("撤销分享", "撤销卡密 ${s.cardCode}？正在使用的访客会被立即断开。", "撤销", destructive = true,
            onConfirm = { vm.revoke(s) }, onDismiss = { revoke = null })
    }
    extend?.let { s ->
        AlertDialog(
            onDismissRequest = { extend = null },
            title = { Text("延长有效期") },
            text = {
                Column {
                    Durations.extend.forEach { d ->
                        TextButton(onClick = { extend = null; vm.extend(s, d.seconds) }, modifier = Modifier.fillMaxWidth()) { Text(d.label) }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { extend = null }) { Text("取消") } },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ShareCard(s: Share, vm: SharesViewModel, onRevoke: () -> Unit, onExtend: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.cardCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                if (s.activeConnections > 0) Pill("在线 ${s.activeConnections}", AppColors.Online, filled = true)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("复制卡密") }, onClick = { menu = false; clipboard.setText(AnnotatedString(s.cardCode)); vm.toast("已复制卡密") })
                        DropdownMenuItem(text = { Text("复制链接") }, onClick = { menu = false; clipboard.setText(AnnotatedString(vm.linkFor(s.token))); vm.toast("已复制链接") })
                        DropdownMenuItem(text = { Text("发送给…") }, onClick = { menu = false; shareText(ctx, shareMessage(s.cardCode, vm.linkFor(s.token), s.expiresAt)) })
                        if (s.expiresAt != null) DropdownMenuItem(text = { Text("延长有效期") }, onClick = { menu = false; onExtend() })
                        DropdownMenuItem(text = { Text("撤销", color = AppColors.Danger) }, onClick = { menu = false; onRevoke() })
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill(s.deviceId, AppColors.Brand)
                Pill(s.accessMode.label, if (s.accessMode == AccessMode.VIEW_ONLY) AppColors.Offline else AppColors.Brand)
                if (s.requirePassword) Pill("需密码", AppColors.Warning)
                if (!s.allowClipboard) Pill("禁剪贴板", AppColors.Offline)
                if (!s.allowFileTx) Pill("禁文件", AppColors.Offline)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append(s.expiresAt?.let { "到期 ${Formatters.shortDateTime(it)}（${Formatters.relative(it)}）" } ?: "永久有效")
                    append(" · 使用 ${s.useCount} 次")
                    s.lastUsedAt?.let { append(" · 最近 ${Formatters.relative(it)}") }
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (s.description.isNotBlank()) Text(s.description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ShareCreateDialog(
    devices: List<Device>,
    presetDevice: String?,
    onDismiss: () -> Unit,
    onCreate: (ShareCreateRequest) -> Unit,
) {
    var deviceId by remember { mutableStateOf(presetDevice?.takeIf { it.isNotBlank() } ?: devices.firstOrNull()?.id.orEmpty()) }
    var expiry by remember { mutableStateOf(Durations.shareExpiry[2]) }
    var mode by remember { mutableStateOf(AccessMode.FULL) }
    var password by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    var clip by remember { mutableStateOf(true) }
    var file by remember { mutableStateOf(true) }
    var deviceMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建分享") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box {
                    OutlinedButton(onClick = { deviceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (deviceId.isBlank()) "选择设备" else "设备：$deviceId")
                    }
                    DropdownMenu(deviceMenu, { deviceMenu = false }) {
                        devices.forEach { d ->
                            DropdownMenuItem(text = { Text(d.id + if (d.online) "" else "（离线）") }, onClick = { deviceId = d.id; deviceMenu = false })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("有效期", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Durations.shareExpiry.forEach { d -> FilterChip(expiry == d, { expiry = d }, { Text(d.label) }) }
                }
                Text("权限", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccessMode.entries.forEach { m -> FilterChip(mode == m, { mode = m }, { Text(m.label) }) }
                }
                ToggleLine("允许剪贴板同步", clip) { clip = it }
                ToggleLine("允许文件传输", file) { file = it }
                PasswordField(password, { password = it }, "访问密码（可选）", Modifier.fillMaxWidth())
                OutlinedTextField(custom, { custom = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(16) }, label = { Text("自定义卡密（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it.take(100) }, label = { Text("备注（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = deviceId.isNotBlank(), onClick = {
                onCreate(ShareCreateRequest(deviceId, expiry.seconds, mode, password, desc.trim(), clip, file, custom))
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ToggleLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

@Composable
private fun ShareResultDialog(r: ShareCreated, link: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分享已创建") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("卡密", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(r.cardCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                }
                Text(r.expiresAt?.let { "有效期至 ${Formatters.dateTime(it)}" } ?: "永久有效", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(r.cardCode)) }) {
                        Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(4.dp)); Text("卡密")
                    }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(link)) }) {
                        Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(4.dp)); Text("链接")
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { shareText(ctx, shareMessage(r.cardCode, link, r.expiresAt)) }) { Text("发送给…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

private fun shareMessage(card: String, link: String, expiresAt: Long?) = buildString {
    append("云手机访问卡密：").append(card).append('\n')
    append("网页访问：").append(link).append('\n')
    append(expiresAt?.let { "有效期至 ${Formatters.dateTime(it)}" } ?: "长期有效")
}

private fun shareText(ctx: android.content.Context, text: String) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "发送分享"))
}
