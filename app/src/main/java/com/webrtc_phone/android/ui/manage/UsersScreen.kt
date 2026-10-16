package com.webrtc_phone.android.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Group
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.core.util.Formatters
import com.webrtc_phone.android.data.model.AdminUser
import com.webrtc_phone.android.data.model.AdminUserCreate
import com.webrtc_phone.android.data.model.UserPolicy
import com.webrtc_phone.android.ui.common.ConfirmDialog
import com.webrtc_phone.android.ui.common.Durations
import com.webrtc_phone.android.ui.common.EmptyState
import com.webrtc_phone.android.ui.common.PasswordField
import com.webrtc_phone.android.ui.common.Pill
import com.webrtc_phone.android.ui.common.StatusDot
import com.webrtc_phone.android.ui.settings.SubScreen
import com.webrtc_phone.android.ui.theme.AppColors

private enum class UserFilter(val label: String) { ALL("全部"), ONLINE("在线"), EXPIRING("即将到期"), EXPIRED("已到期") }

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun UsersScreen(vm: UsersViewModel, currentUser: String, onBack: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(UserFilter.ALL) }
    var create by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AdminUser?>(null) }
    var resetting by remember { mutableStateOf<AdminUser?>(null) }
    var noting by remember { mutableStateOf<AdminUser?>(null) }
    var deleting by remember { mutableStateOf<AdminUser?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val now = System.currentTimeMillis()
    val shown = st.items.filter { u ->
        val q = query.trim().lowercase()
        (q.isEmpty() || u.username.lowercase().contains(q) || u.note.lowercase().contains(q)) && when (filter) {
            UserFilter.ALL -> true
            UserFilter.ONLINE -> u.online
            UserFilter.EXPIRING -> u.expiresAt != null && u.expiresAt > now && u.expiresAt - now < 7 * 86400_000L
            UserFilter.EXPIRED -> u.isExpired(now)
        }
    }

    SubScreen(
        "用户管理", onBack,
        floating = { ExtendedFloatingActionButton(onClick = { create = true }, icon = { Icon(Icons.Filled.PersonAdd, null) }, text = { Text("新建用户") }) },
        snackbar = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                query, { query = it }, placeholder = { Text("搜索用户名 / 备注") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UserFilter.entries.forEach { f -> FilterChip(filter == f, { filter = f }, { Text(f.label) }) }
            }
            PullToRefreshBox(st.loading, onRefresh = { vm.load() }) {
                if (shown.isEmpty() && !st.loading) EmptyState(Icons.Outlined.Group, st.error ?: "没有用户")
                else LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown, key = { it.username }) { u ->
                        UserCard(
                            u, isSelf = u.username == currentUser,
                            onEdit = { editing = u }, onNote = { noting = u }, onReset = { resetting = u },
                            onKick = { vm.kick(u) }, onDelete = { deleting = u },
                        )
                    }
                }
            }
        }
    }

    if (create) CreateUserDialog(onDismiss = { create = false }, onCreate = { create = false; vm.create(it) })
    editing?.let { u -> EditUserDialog(u, onDismiss = { editing = null }, onSave = { exp, p -> editing = null; vm.update(u, exp, p) }) }
    noting?.let { u ->
        TextInputDialog("修改备注", u.note, "备注", onDismiss = { noting = null }) { noting = null; vm.updateNote(u, it) }
    }
    resetting?.let { u ->
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { resetting = null },
            title = { Text("重置 ${u.username} 的密码") },
            text = { PasswordField(pwd, { pwd = it }, "新密码（至少 6 位）", Modifier.fillMaxWidth()) },
            confirmButton = { Button(enabled = pwd.length >= 6, onClick = { resetting = null; vm.resetPassword(u, pwd) }) { Text("重置") } },
            dismissButton = { TextButton(onClick = { resetting = null }) { Text("取消") } },
        )
    }
    deleting?.let { u ->
        ConfirmDialog("删除用户", "删除 ${u.username}？其登录凭据与租约将立即失效。", "删除", destructive = true,
            onConfirm = { vm.delete(u) }, onDismiss = { deleting = null })
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun UserCard(
    u: AdminUser, isSelf: Boolean,
    onEdit: () -> Unit, onNote: () -> Unit, onReset: () -> Unit, onKick: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (u.online) AppColors.Online else AppColors.Offline, size = 10)
                Spacer(Modifier.width(8.dp))
                Text(u.username, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (u.isAdmin) Pill("管理员", AppColors.Brand, filled = true)
                if (isSelf) Pill("我", AppColors.Online)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("有效期与权限") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("修改备注") }, onClick = { menu = false; onNote() })
                        DropdownMenuItem(text = { Text("重置密码") }, onClick = { menu = false; onReset() })
                        if (u.online && !isSelf) DropdownMenuItem(text = { Text("踢下线") }, onClick = { menu = false; onKick() })
                        if (!isSelf) DropdownMenuItem(text = { Text("删除", color = AppColors.Danger) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (u.note.isNotBlank()) Text(u.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val exp = u.expiresAt
                when {
                    exp == null -> Pill("永久", AppColors.Online)
                    u.isExpired() -> Pill("已到期", AppColors.Danger, filled = true)
                    else -> Pill("剩 ${Formatters.duration((exp - System.currentTimeMillis()) / 1000)}", if (exp - System.currentTimeMillis() < 7 * 86400_000L) AppColors.Warning else AppColors.Brand)
                }
                u.activeDevices.forEach { Pill("使用 $it", AppColors.Busy) }
                val p = u.policy
                if (p.forbidTerminal) Pill("禁终端", AppColors.Offline)
                if (p.forbidFilePush) Pill("禁文件", AppColors.Offline)
                if (p.forbidBitrate || p.forbidFps || p.forbidResolution) Pill("画质锁定", AppColors.Offline)
                if (p.forbidAudio) Pill("禁音频", AppColors.Offline)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CreateUserDialog(onDismiss: () -> Unit, onCreate: (AdminUserCreate) -> Unit) {
    var name by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf(false) }
    var exp by remember { mutableStateOf(Durations.account[0]) }
    val valid = name.isNotBlank() && pwd.length >= 6
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建用户") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it.trim().take(32) }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                PasswordField(pwd, { pwd = it }, "密码（至少 6 位）", Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it.take(100) }, label = { Text("备注") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("有效期", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Durations.account.forEach { d -> FilterChip(exp == d, { exp = d }, { Text(d.label) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("管理员权限", Modifier.weight(1f))
                    Switch(admin, { admin = it })
                }
            }
        },
        confirmButton = { Button(enabled = valid, onClick = { onCreate(AdminUserCreate(name, pwd, if (admin) "admin" else "user", note, exp.seconds)) }) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditUserDialog(u: AdminUser, onDismiss: () -> Unit, onSave: (Long, UserPolicy) -> Unit) {
    // -1 = 不修改有效期
    var exp by remember { mutableStateOf(-1L) }
    var p by remember { mutableStateOf(u.policy) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(u.username) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "当前：" + (u.expiresAt?.let { Formatters.dateTime(it) } ?: "永久有效"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("重设有效期（从现在起算）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(exp == -1L, { exp = -1L }, { Text("不变") })
                    Durations.account.forEach { d -> FilterChip(exp == d.seconds, { exp = d.seconds }, { Text(d.label) }) }
                }
                Spacer(Modifier.height(8.dp))
                Text("功能限制", style = MaterialTheme.typography.labelLarge)
                PolicyLine("禁止调整码率", p.forbidBitrate) { p = p.copy(forbidBitrate = it) }
                PolicyLine("禁止调整帧率", p.forbidFps) { p = p.copy(forbidFps = it) }
                PolicyLine("禁止调整分辨率", p.forbidResolution) { p = p.copy(forbidResolution = it) }
                PolicyLine("禁止音频", p.forbidAudio) { p = p.copy(forbidAudio = it) }
                PolicyLine("禁止文件传输", p.forbidFilePush) { p = p.copy(forbidFilePush = it) }
                PolicyLine("禁止终端", p.forbidTerminal) { p = p.copy(forbidTerminal = it) }
                PolicyLine("禁止创建分享", p.forbidShare) { p = p.copy(forbidShare = it) }
            }
        },
        confirmButton = { Button(onClick = { onSave(exp, p) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PolicyLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, label: String, singleLine: Boolean = true, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, label = { Text(label) }, singleLine = singleLine, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { onConfirm(v.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
