package com.webrtc_phone.android.ui.manage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NoCell
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.core.util.Formatters
import com.webrtc_phone.android.data.model.AdminLease
import com.webrtc_phone.android.data.model.AdminUser
import com.webrtc_phone.android.data.model.AdminUserCreate
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.UserPolicy
import com.webrtc_phone.android.ui.common.ConfirmDialog
import com.webrtc_phone.android.ui.common.DurationOption
import com.webrtc_phone.android.ui.common.Durations
import com.webrtc_phone.android.ui.common.EmptyState
import com.webrtc_phone.android.ui.common.PasswordField
import com.webrtc_phone.android.ui.common.Pill
import com.webrtc_phone.android.ui.common.StatusDot
import com.webrtc_phone.android.ui.settings.SubScreen
import com.webrtc_phone.android.ui.theme.AppColors

private enum class UserFilter(val label: String) {
    ALL("全部"),
    ONLINE("在线"),
    LEASED("租约中"),
    EXPIRING("即将到期"),
    EXPIRED("已到期"),
    ADMIN("管理员"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UsersScreen(vm: UsersViewModel, currentUser: String, onBack: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(UserFilter.ALL) }

    // 操作对话框状态
    var create by remember { mutableStateOf(false) }
    var detailUser by remember { mutableStateOf<AdminUser?>(null) }
    var initialDetailTab by remember { mutableIntStateOf(0) }
    var createLeaseUser by remember { mutableStateOf<AdminUser?>(null) }
    var resetting by remember { mutableStateOf<AdminUser?>(null) }
    var noting by remember { mutableStateOf<AdminUser?>(null) }
    var deleting by remember { mutableStateOf<AdminUser?>(null) }

    var extendingLease by remember { mutableStateOf<AdminLease?>(null) }
    var revokingLease by remember { mutableStateOf<AdminLease?>(null) }
    var notingLease by remember { mutableStateOf<AdminLease?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val now = System.currentTimeMillis()
    val shown = st.users.filter { u ->
        val q = query.trim().lowercase()
        val userActiveLeases = st.activeLeasesByUser[u.username].orEmpty()
        val matchesQuery = q.isEmpty() || u.username.lowercase().contains(q) || u.note.lowercase().contains(q)
        val matchesFilter = when (filter) {
            UserFilter.ALL -> true
            UserFilter.ONLINE -> u.online
            UserFilter.LEASED -> userActiveLeases.isNotEmpty()
            UserFilter.EXPIRING -> u.expiresAt != null && u.expiresAt > now && (u.expiresAt - now < 7 * 86400_000L)
            UserFilter.EXPIRED -> u.isExpired(now)
            UserFilter.ADMIN -> u.isAdmin
        }
        matchesQuery && matchesFilter
    }

    SubScreen(
        "用户管理", onBack,
        floating = {
            ExtendedFloatingActionButton(
                onClick = { create = true },
                icon = { Icon(Icons.Filled.PersonAdd, null) },
                text = { Text("新建用户") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbar = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            // 顶部统计微看板
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatMiniCard(
                    label = "全部用户",
                    value = st.totalUsers.toString(),
                    selected = filter == UserFilter.ALL,
                    modifier = Modifier.weight(1f),
                    onClick = { filter = UserFilter.ALL }
                )
                StatMiniCard(
                    label = "在线",
                    value = st.onlineUsers.toString(),
                    color = AppColors.Online,
                    selected = filter == UserFilter.ONLINE,
                    modifier = Modifier.weight(1f),
                    onClick = { filter = UserFilter.ONLINE }
                )
                StatMiniCard(
                    label = "活跃租约",
                    value = st.totalActiveLeases.toString(),
                    color = AppColors.Brand,
                    selected = filter == UserFilter.LEASED,
                    modifier = Modifier.weight(1f),
                    onClick = { filter = UserFilter.LEASED }
                )
                StatMiniCard(
                    label = "管理员",
                    value = st.adminUsers.toString(),
                    color = MaterialTheme.colorScheme.secondary,
                    selected = filter == UserFilter.ADMIN,
                    modifier = Modifier.weight(1f),
                    onClick = { filter = UserFilter.ADMIN }
                )
            }

            // 搜索框
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索用户名 / 备注") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, "清除")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // 快速分类过滤 Chips
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UserFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) },
                        shape = RoundedCornerShape(50),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 列表与下拉刷新
            PullToRefreshBox(
                isRefreshing = st.loading,
                onRefresh = { vm.load() },
                modifier = Modifier.fillMaxSize()
            ) {
                if (shown.isEmpty() && !st.loading) {
                    EmptyState(Icons.Outlined.Group, st.error ?: "未找到匹配用户")
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(shown, key = { it.username }) { u ->
                            val userActiveLeases = st.activeLeasesByUser[u.username].orEmpty()
                            val userAllLeases = st.allLeasesByUser[u.username].orEmpty()
                            UserCard(
                                u = u,
                                isSelf = u.username == currentUser,
                                activeLeases = userActiveLeases,
                                totalLeaseCount = userAllLeases.size,
                                onDetail = { tab ->
                                    initialDetailTab = tab
                                    detailUser = u
                                },
                                onCreateLease = { createLeaseUser = u },
                                onEdit = {
                                    initialDetailTab = 2 // 权限 Tab
                                    detailUser = u
                                },
                                onNote = { noting = u },
                                onReset = { resetting = u },
                                onKick = { vm.kick(u) },
                                onDelete = { deleting = u },
                            )
                        }
                    }
                }
            }
        }
    }

    // 新建用户对话框
    if (create) {
        CreateUserDialog(
            onDismiss = { create = false },
            onCreate = { req ->
                create = false
                vm.create(req)
            }
        )
    }

    // 全功能用户详情与管理抽屉（包含设备开租、租约列表、展期、权限管理）
    detailUser?.let { u ->
        val userAllLeases = st.allLeasesByUser[u.username].orEmpty()
        UserDetailSheet(
            user = u,
            isSelf = u.username == currentUser,
            leases = userAllLeases,
            idleDevices = st.idleDevices,
            allDevices = st.devices,
            initialTab = initialDetailTab,
            onDismiss = { detailUser = null },
            onCreateLease = { devId, dur, note ->
                vm.createLease(u.username, devId, dur, note)
            },
            onExtendLease = { lease -> extendingLease = lease },
            onRevokeLease = { lease -> revokingLease = lease },
            onUpdateLeaseNote = { lease -> notingLease = lease },
            onUpdateUser = { exp, policy ->
                vm.update(u, exp, policy)
            },
            onUpdateNote = { noting = u },
            onResetPassword = { resetting = u },
            onKick = { vm.kick(u) },
            onKickDevice = { devId -> vm.kick(u, devId) },
            onDelete = { deleting = u },
        )
    }

    // 独立快捷开租弹窗
    createLeaseUser?.let { u ->
        CreateLeaseDialog(
            user = u,
            idleDevices = st.idleDevices,
            allDevices = st.devices,
            onDismiss = { createLeaseUser = null },
            onConfirm = { devId, dur, note ->
                createLeaseUser = null
                vm.createLease(u.username, devId, dur, note)
            }
        )
    }

    // 租约展期弹窗
    extendingLease?.let { lease ->
        ExtendLeaseDialog(
            lease = lease,
            onDismiss = { extendingLease = null },
            onConfirm = { addedSeconds ->
                extendingLease = null
                vm.extendLease(lease, addedSeconds)
            }
        )
    }

    // 租约收回二次确认
    revokingLease?.let { lease ->
        ConfirmDialog(
            title = "收回设备租约",
            message = "确定收回用户 ${lease.username} 对设备 ${lease.deviceId} 的租约？收回后该用户在此设备上的连接将被立即切断。",
            confirmText = "确认收回",
            destructive = true,
            onConfirm = {
                revokingLease = null
                vm.revokeLease(lease)
            },
            onDismiss = { revokingLease = null }
        )
    }

    // 租约备注修改弹窗
    notingLease?.let { lease ->
        TextInputDialog(
            title = "修改租约备注",
            initial = lease.note,
            label = "租约备注（如订单号/渠道）",
            onDismiss = { notingLease = null }
        ) { newNote ->
            notingLease = null
            vm.updateLeaseNote(lease, newNote)
        }
    }

    // 修改用户备注弹窗
    noting?.let { u ->
        TextInputDialog(
            title = "修改用户备注",
            initial = u.note,
            label = "备注",
            onDismiss = { noting = null }
        ) {
            noting = null
            vm.updateNote(u, it)
        }
    }

    // 重置密码弹窗
    resetting?.let { u ->
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { resetting = null },
            title = { Text("重置 ${u.username} 的密码") },
            text = {
                PasswordField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = "新密码（至少 6 位）",
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = pwd.length >= 6,
                    onClick = {
                        resetting = null
                        vm.resetPassword(u, pwd)
                    }
                ) { Text("重置密码") }
            },
            dismissButton = { TextButton(onClick = { resetting = null }) { Text("取消") } },
        )
    }

    // 删除用户确认弹窗
    deleting?.let { u ->
        ConfirmDialog(
            title = "删除用户",
            message = "确定删除用户 ${u.username}？其登录凭据与关联的全部设备租约将立即失效并收回。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                deleting = null
                vm.delete(u)
            },
            onDismiss = { deleting = null }
        )
    }
}

/** 顶部小统计微卡片 */
@Composable
private fun StatMiniCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, color) else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 现代用户列表卡片 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserCard(
    u: AdminUser,
    isSelf: Boolean,
    activeLeases: List<AdminLease>,
    totalLeaseCount: Int,
    onDetail: (initialTab: Int) -> Unit,
    onCreateLease: () -> Unit,
    onEdit: () -> Unit,
    onNote: () -> Unit,
    onReset: () -> Unit,
    onKick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDetail(0) }
    ) {
        Column(Modifier.padding(14.dp)) {
            // 头部：头像 + 用户名 + 角色 + 更多操作
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 用户头像带在线光标
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(
                                if (u.isAdmin) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = u.username.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (u.isAdmin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(if (u.online) AppColors.Online else AppColors.Offline, CircleShape)
                            .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = u.username,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (u.isAdmin) {
                            Pill("👑 管理员", AppColors.Brand, filled = true)
                        }
                        if (isSelf) {
                            Pill("我", AppColors.Online)
                        }
                    }
                    if (u.note.isNotBlank()) {
                        Text(
                            text = u.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                // 操作溢出菜单
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, "更多")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, null) },
                            text = { Text("设备开租 / 租约") },
                            onClick = { menu = false; onDetail(0) }
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Outlined.Security, null) },
                            text = { Text("功能限制策略") },
                            onClick = { menu = false; onDetail(2) }
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            text = { Text("修改备注") },
                            onClick = { menu = false; onNote() }
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Outlined.Key, null) },
                            text = { Text("重置密码") },
                            onClick = { menu = false; onReset() }
                        )
                        if (u.online && !isSelf) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Outlined.PowerSettingsNew, null) },
                                text = { Text("踢下线") },
                                onClick = { menu = false; onKick() }
                            )
                        }
                        if (!isSelf) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = AppColors.Danger) },
                                text = { Text("删除用户", color = AppColors.Danger) },
                                onClick = { menu = false; onDelete() }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 状态胶囊组
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 账号有效期
                val exp = u.expiresAt
                val now = System.currentTimeMillis()
                when {
                    exp == null -> Pill("长期有效", AppColors.Online)
                    u.isExpired(now) -> Pill("账号已到期", AppColors.Danger, filled = true)
                    else -> {
                        val remainSec = (exp - now) / 1000
                        val isExpiringSoon = remainSec < 7 * 86400
                        Pill(
                            "账号剩 ${Formatters.duration(remainSec)}",
                            if (isExpiringSoon) AppColors.Warning else AppColors.Brand
                        )
                    }
                }

                // 租约微标（点击直达租约）
                if (activeLeases.isNotEmpty()) {
                    if (activeLeases.size == 1) {
                        val l = activeLeases[0]
                        val lRemain = if (l.isPermanent) "永久" else Formatters.duration(l.remainingSeconds)
                        Pill("📦 租用 ${l.deviceId} ($lRemain)", if (l.isExpiringSoon()) AppColors.Warning else AppColors.Brand)
                    } else {
                        Pill("📦 ${activeLeases.size}台设备租约中", AppColors.Brand)
                    }
                } else if (totalLeaseCount > 0) {
                    Pill("📦 历史租约 $totalLeaseCount", AppColors.Offline)
                }

                // 正在控制的在线会话
                u.activeDevices.forEach { devId ->
                    Pill("在线控制 $devId", AppColors.Busy)
                }

                // 权限限制
                val p = u.policy
                if (p.forbidTerminal) Pill("禁终端", AppColors.Offline)
                if (p.forbidFilePush) Pill("禁文件", AppColors.Offline)
                if (p.forbidBitrate || p.forbidFps || p.forbidResolution) Pill("锁画质", AppColors.Offline)
                if (p.forbidAudio) Pill("禁音频", AppColors.Offline)
                if (p.forbidShare) Pill("禁分享", AppColors.Offline)
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(8.dp))

            // 底部快捷操作行
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (activeLeases.isNotEmpty()) "已分配 ${activeLeases.size} 台设备" else "尚未分配设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onCreateLease,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("开租", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { onDetail(0) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text(if (activeLeases.isNotEmpty()) "管理租约" else "详情", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * 全功能现代用户详情与管控抽屉
 * 支持：设备租约一键开租、展期、收回；账号有效期、密码重置、踢下线；细粒度管控策略等
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun UserDetailSheet(
    user: AdminUser,
    isSelf: Boolean,
    leases: List<AdminLease>,
    idleDevices: List<Device>,
    allDevices: List<Device>,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onCreateLease: (deviceId: String, durationSeconds: Long, note: String) -> Unit,
    onExtendLease: (AdminLease) -> Unit,
    onRevokeLease: (AdminLease) -> Unit,
    onUpdateLeaseNote: (AdminLease) -> Unit,
    onUpdateUser: (expireSeconds: Long, policy: UserPolicy) -> Unit,
    onUpdateNote: () -> Unit,
    onResetPassword: () -> Unit,
    onKick: () -> Unit,
    onKickDevice: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var tabIndex by remember { mutableIntStateOf(initialTab) }
    var showInlineCreateLease by remember { mutableStateOf(false) }

    // 账号与策略编辑状态
    var expireChoice by remember { mutableLongStateOf(-1L) } // -1 = 保持不变, 0 = 永久
    var currentPolicy by remember { mutableStateOf(user.policy) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            // 头部用户信息
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (user.isAdmin) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        user.username.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (user.isAdmin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(user.username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Pill(if (user.isAdmin) "👑 管理员" else "普通用户", if (user.isAdmin) AppColors.Brand else AppColors.Online)
                        Pill(if (user.online) "在线" else "离线", if (user.online) AppColors.Online else AppColors.Offline)
                    }
                    Text(
                        user.note.ifBlank { "暂无备注" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, "关闭")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Tab 导航
            PrimaryTabRow(
                selectedTabIndex = tabIndex,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text("设备租约 (${leases.count { it.active }})") },
                    icon = { Icon(Icons.Outlined.PhoneAndroid, null) }
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = { Text("账号与安全") },
                    icon = { Icon(Icons.Outlined.Security, null) }
                )
                Tab(
                    selected = tabIndex == 2,
                    onClick = { tabIndex = 2 },
                    text = { Text("功能限制") },
                    icon = { Icon(Icons.Outlined.Shield, null) }
                )
            }

            Spacer(Modifier.height(10.dp))

            // 抽屉主要内容
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                when (tabIndex) {
                    0 -> LeaseManagementTab(
                        user = user,
                        leases = leases,
                        idleDevices = idleDevices,
                        allDevices = allDevices,
                        showCreateForm = showInlineCreateLease,
                        onToggleCreateForm = { showInlineCreateLease = !showInlineCreateLease },
                        onCreateLease = { devId, dur, note ->
                            showInlineCreateLease = false
                            onCreateLease(devId, dur, note)
                        },
                        onExtendLease = onExtendLease,
                        onRevokeLease = onRevokeLease,
                        onUpdateLeaseNote = onUpdateLeaseNote,
                        onKickDevice = onKickDevice,
                    )
                    1 -> AccountSecurityTab(
                        user = user,
                        isSelf = isSelf,
                        expireChoice = expireChoice,
                        onExpireChange = { expireChoice = it },
                        onSaveExpire = {
                            if (expireChoice != -1L) {
                                onUpdateUser(expireChoice, user.policy)
                            }
                        },
                        onEditNote = onUpdateNote,
                        onResetPassword = onResetPassword,
                        onKick = onKick,
                        onDelete = onDelete,
                    )
                    2 -> PolicyTab(
                        policy = currentPolicy,
                        onPolicyChange = { currentPolicy = it },
                        onSave = {
                            onUpdateUser(expireChoice, currentPolicy)
                        }
                    )
                }
            }
        }
    }
}

/** Tab 0: 设备租约管理 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeaseManagementTab(
    user: AdminUser,
    leases: List<AdminLease>,
    idleDevices: List<Device>,
    allDevices: List<Device>,
    showCreateForm: Boolean,
    onToggleCreateForm: () -> Unit,
    onCreateLease: (deviceId: String, durationSeconds: Long, note: String) -> Unit,
    onExtendLease: (AdminLease) -> Unit,
    onRevokeLease: (AdminLease) -> Unit,
    onUpdateLeaseNote: (AdminLease) -> Unit,
    onKickDevice: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        // 当前控制设备提示（可踢）
        if (user.activeDevices.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AppColors.Busy.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("当前正在控制的设备", style = MaterialTheme.typography.labelMedium, color = AppColors.Busy, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        user.activeDevices.forEach { devId ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Busy)
                            ) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(AppColors.Busy, size = 8)
                                    Spacer(Modifier.width(6.dp))
                                    Text(devId, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "切断",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppColors.Danger,
                                        modifier = Modifier.clickable { onKickDevice(devId) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 租约操作栏：标题 + 开租按键
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("设备租约列表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Button(
                onClick = onToggleCreateForm,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(if (showCreateForm) Icons.Outlined.Close else Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (showCreateForm) "取消开租" else "开通新租约")
            }
        }

        Spacer(Modifier.height(8.dp))

        // 内联开租表单
        AnimatedVisibility(visible = showCreateForm) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                InlineCreateLeaseForm(
                    idleDevices = idleDevices,
                    allDevices = allDevices,
                    onCancel = onToggleCreateForm,
                    onSubmit = onCreateLease,
                )
            }
        }

        // 租约列表
        val sortedLeases = remember(leases) {
            leases.sortedWith(compareByDescending<AdminLease> { it.active }.thenByDescending { it.createdAt ?: 0L })
        }

        if (sortedLeases.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.PhoneAndroid, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("该用户当前未持有任何设备租约", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("点击上方「开通新租约」分配云手机使用权", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sortedLeases.forEach { lease ->
                    LeaseItemCard(
                        lease = lease,
                        onExtend = { onExtendLease(lease) },
                        onRevoke = { onRevokeLease(lease) },
                        onUpdateNote = { onUpdateLeaseNote(lease) }
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 单条租约展示卡片 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeaseItemCard(
    lease: AdminLease,
    onExtend: () -> Unit,
    onRevoke: () -> Unit,
    onUpdateNote: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (lease.active) MaterialTheme.colorScheme.surfaceContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(12.dp),
        border = if (lease.active) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
    ) {
        Column(Modifier.padding(12.dp)) {
            // 状态行
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    color = when {
                        lease.isRevoked -> AppColors.Danger
                        lease.active -> AppColors.Online
                        else -> AppColors.Offline
                    },
                    size = 10
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = lease.deviceId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                // 状态 Pill
                when {
                    lease.isRevoked -> Pill("已收回", AppColors.Danger, filled = true)
                    lease.active -> Pill(
                        if (lease.isPermanent) "永久有效" else "剩余 ${Formatters.duration(lease.remainingSeconds)}",
                        if (lease.isExpiringSoon()) AppColors.Warning else AppColors.Online,
                        filled = true
                    )
                    else -> Pill("已到期", AppColors.Offline)
                }
            }

            Spacer(Modifier.height(6.dp))

            // 租约元信息
            if (lease.note.isNotBlank()) {
                Text(
                    text = "备注：${lease.note}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "开通时间：" + (lease.createdAt?.let { Formatters.dateTime(it) } ?: "-"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                if (lease.revokedAt != null) {
                    Text(
                        text = "收回：" + Formatters.dateTime(lease.revokedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.Danger.copy(alpha = 0.8f)
                    )
                }
            }

            // 活跃租约的操作按钮
            if (lease.active) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(6.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onUpdateNote,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("改备注", fontSize = 12.sp)
                    }

                    Spacer(Modifier.width(4.dp))

                    OutlinedButton(
                        onClick = onExtend,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Update, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("展期", fontSize = 12.sp)
                    }

                    Spacer(Modifier.width(6.dp))

                    Button(
                        onClick = onRevoke,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Danger.copy(alpha = 0.15f), contentColor = AppColors.Danger)
                    ) {
                        Text("收回", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** 内联开租表单 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineCreateLeaseForm(
    idleDevices: List<Device>,
    allDevices: List<Device>,
    onCancel: () -> Unit,
    onSubmit: (deviceId: String, durationSeconds: Long, note: String) -> Unit,
) {
    var selectedDeviceId by remember { mutableStateOf(idleDevices.firstOrNull()?.id ?: allDevices.firstOrNull()?.id ?: "") }
    var selectedDuration by remember { mutableStateOf(Durations.lease[4]) } // 默认 1 天
    var isCustomDuration by remember { mutableStateOf(false) }
    var customVal by remember { mutableStateOf("1") }
    var customUnit by remember { mutableStateOf("day") } // minute, hour, day
    var note by remember { mutableStateOf("") }

    var expandedDeviceSelect by remember { mutableStateOf(false) }

    Column(Modifier.padding(14.dp)) {
        Text("开通设备租约", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        // 1. 选择设备
        Text("选择设备：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))

        Box {
            OutlinedButton(
                onClick = { expandedDeviceSelect = true },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    val currentDev = allDevices.firstOrNull { it.id == selectedDeviceId }
                    Text(
                        if (selectedDeviceId.isBlank()) "请选择要出租的设备"
                        else "$selectedDeviceId" + (if (currentDev?.info?.title?.isNotBlank() == true) "（${currentDev.info.title}）" else ""),
                        maxLines = 1
                    )
                    Text("▾")
                }
            }

            DropdownMenu(
                expanded = expandedDeviceSelect,
                onDismissRequest = { expandedDeviceSelect = false },
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                if (idleDevices.isNotEmpty()) {
                    Text("—— 闲置可用设备 ——", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    idleDevices.forEach { dev ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(if (dev.online) AppColors.Online else AppColors.Offline, size = 8)
                                    Spacer(Modifier.width(8.dp))
                                    Text("${dev.id}" + (if (dev.info.title.isNotBlank()) " (${dev.info.title})" else ""))
                                }
                            },
                            onClick = {
                                selectedDeviceId = dev.id
                                expandedDeviceSelect = false
                            }
                        )
                    }
                }

                val busyDevices = allDevices.filter { it !in idleDevices }
                if (busyDevices.isNotEmpty()) {
                    Text("—— 已有租约设备 (开租将产生冲突) ——", style = MaterialTheme.typography.labelSmall, color = AppColors.Busy, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    busyDevices.forEach { dev ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(AppColors.Busy, size = 8)
                                    Spacer(Modifier.width(8.dp))
                                    Text("${dev.id}（已有租约）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                selectedDeviceId = dev.id
                                expandedDeviceSelect = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 2. 选择租约时长
        Text("租用时长：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Durations.lease.forEach { opt ->
                FilterChip(
                    selected = !isCustomDuration && selectedDuration == opt,
                    onClick = {
                        isCustomDuration = false
                        selectedDuration = opt
                    },
                    label = { Text(opt.label) },
                    shape = RoundedCornerShape(8.dp),
                )
            }
            FilterChip(
                selected = isCustomDuration,
                onClick = { isCustomDuration = true },
                label = { Text("自定义时长") },
                shape = RoundedCornerShape(8.dp),
            )
        }

        if (isCustomDuration) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = customVal,
                    onValueChange = { customVal = it.filter { ch -> ch.isDigit() } },
                    label = { Text("数值") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.width(90.dp)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = customUnit == "minute", onClick = { customUnit = "minute" }, label = { Text("分钟") })
                    FilterChip(selected = customUnit == "hour", onClick = { customUnit = "hour" }, label = { Text("小时") })
                    FilterChip(selected = customUnit == "day", onClick = { customUnit = "天" }, label = { Text("天") })
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 3. 租约备注
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(80) },
            label = { Text("备注（选填，如订单号 / 用途）") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))

        // 操作按钮
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = selectedDeviceId.isNotBlank(),
                onClick = {
                    val finalSeconds = if (isCustomDuration) {
                        val num = customVal.toLongOrNull() ?: 1L
                        when (customUnit) {
                            "minute" -> num * 60L
                            "hour" -> num * 3600L
                            else -> num * 86400L
                        }
                    } else {
                        selectedDuration.seconds
                    }
                    onSubmit(selectedDeviceId, finalSeconds, note.trim())
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("确认开租")
            }
        }
    }
}

/** Tab 1: 账号与安全 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountSecurityTab(
    user: AdminUser,
    isSelf: Boolean,
    expireChoice: Long,
    onExpireChange: (Long) -> Unit,
    onSaveExpire: () -> Unit,
    onEditNote: () -> Unit,
    onResetPassword: () -> Unit,
    onKick: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 账号有效期区块
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("账号登录有效期", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                val exp = user.expiresAt
                Text(
                    text = "当前：" + (exp?.let { "${Formatters.dateTime(it)} (${Formatters.relative(it)})" } ?: "长期有效"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text("重置账号有效期（从现在起算）：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = expireChoice == -1L,
                        onClick = { onExpireChange(-1L) },
                        label = { Text("保持不变") },
                        shape = RoundedCornerShape(8.dp)
                    )
                    Durations.account.forEach { opt ->
                        FilterChip(
                            selected = expireChoice == opt.seconds,
                            onClick = { onExpireChange(opt.seconds) },
                            label = { Text(opt.label) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
                if (expireChoice != -1L) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onSaveExpire,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("保存账号有效期")
                    }
                }
            }
        }

        // 基础操作区块
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("快捷操作", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEditNote, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                        Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("修改备注")
                    }
                    OutlinedButton(onClick = onResetPassword, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                        Icon(Icons.Outlined.Key, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重置密码")
                    }
                }

                if (user.online && !isSelf) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onKick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Danger)
                    ) {
                        Icon(Icons.Outlined.PowerSettingsNew, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("强制切断所有连接踢下线")
                    }
                }
            }
        }

        // 危险区域：删除用户
        if (!isSelf) {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.Danger.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Danger.copy(alpha = 0.3f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("危险区域", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.Danger)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "删除此用户将永久注销登录凭据，并立即收回其名下的所有设备租约。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Danger),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除该用户")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Tab 2: 功能限制策略 */
@Composable
private fun PolicyTab(
    policy: UserPolicy,
    onPolicyChange: (UserPolicy) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Text("功能限制策略", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "开启某项限制后，该普通用户在投屏控制时对应能力将被禁用或锁定。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                PolicyLine("禁止调整码率", "限制用户自行修改视频推流码率", policy.forbidBitrate) {
                    onPolicyChange(policy.copy(forbidBitrate = it))
                }
                PolicyLine("禁止调整帧率", "限制用户自行修改推流帧率 (FPS)", policy.forbidFps) {
                    onPolicyChange(policy.copy(forbidFps = it))
                }
                PolicyLine("禁止调整分辨率", "限制用户切换清晰度与缩放规格", policy.forbidResolution) {
                    onPolicyChange(policy.copy(forbidResolution = it))
                }
                PolicyLine("禁止音频内录与监听", "禁止开启手机音频输出与麦克风监听", policy.forbidAudio) {
                    onPolicyChange(policy.copy(forbidAudio = it))
                }
                PolicyLine("禁止文件传输", "禁止使用 WebRTC 文件中心向手机推送或读取文件", policy.forbidFilePush) {
                    onPolicyChange(policy.copy(forbidFilePush = it))
                }
                PolicyLine("禁止终端控制台", "禁止在投屏控制中使用远程 Shell 终端", policy.forbidTerminal) {
                    onPolicyChange(policy.copy(forbidTerminal = it))
                }
                PolicyLine("禁止创建分享与卡密", "禁止该用户对外生成设备分享卡密", policy.forbidShare) {
                    onPolicyChange(policy.copy(forbidShare = it))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onSave,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存功能限制策略")
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PolicyLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 独立开租对话框（快捷入口） */
@Composable
private fun CreateLeaseDialog(
    user: AdminUser,
    idleDevices: List<Device>,
    allDevices: List<Device>,
    onDismiss: () -> Unit,
    onConfirm: (deviceId: String, durationSeconds: Long, note: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("为「${user.username}」开通设备租约")
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                InlineCreateLeaseForm(
                    idleDevices = idleDevices,
                    allDevices = allDevices,
                    onCancel = onDismiss,
                    onSubmit = onConfirm,
                )
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

/** 租约展期对话框 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtendLeaseDialog(
    lease: AdminLease,
    onDismiss: () -> Unit,
    onConfirm: (addedSeconds: Long) -> Unit,
) {
    var selectedOption by remember { mutableStateOf(Durations.leaseExtend[3]) } // 默认 +1 天
    var isCustom by remember { mutableStateOf(false) }
    var customDays by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("租约展期") },
        text = {
            Column {
                Text(
                    text = "为设备 ${lease.deviceId} 延长租期。当前租约剩余：" +
                            (if (lease.isPermanent) "永久有效" else Formatters.duration(lease.remainingSeconds)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text("选择延长时长：", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Durations.leaseExtend.forEach { opt ->
                        FilterChip(
                            selected = !isCustom && selectedOption == opt,
                            onClick = {
                                isCustom = false
                                selectedOption = opt
                            },
                            label = { Text(opt.label) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    FilterChip(
                        selected = isCustom,
                        onClick = { isCustom = true },
                        label = { Text("自定义天数") },
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                if (isCustom) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customDays,
                        onValueChange = { customDays = it.filter { ch -> ch.isDigit() } },
                        label = { Text("增加天数") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val addSec = if (isCustom) {
                        (customDays.toLongOrNull() ?: 1L) * 86400L
                    } else {
                        selectedOption.seconds
                    }
                    onConfirm(addSec)
                }
            ) { Text("确认展期") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 新建用户对话框 */
@OptIn(ExperimentalLayoutApi::class)
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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim().take(32) },
                    label = { Text("用户名") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = "初始密码（至少 6 位）",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(100) },
                    label = { Text("备注（选填）") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text("账号有效期", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Durations.account.forEach { d ->
                        FilterChip(
                            selected = exp == d,
                            onClick = { exp = d },
                            label = { Text(d.label) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设为系统管理员", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = admin, onCheckedChange = { admin = it })
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = { onCreate(AdminUserCreate(name, pwd, if (admin) "admin" else "user", note, exp.seconds)) }
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = v,
                onValueChange = { v = it },
                label = { Text(label) },
                singleLine = singleLine,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(v.trim()) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
