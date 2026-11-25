package com.webrtc_phone.android.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.rtc.StreamTarget
import com.webrtc_phone.android.ui.common.CertificateTrustDialog
import com.webrtc_phone.android.ui.common.PasswordField
import com.webrtc_phone.android.ui.common.StatusDot
import com.webrtc_phone.android.ui.stream.StreamLauncher
import com.webrtc_phone.android.ui.theme.AppColors
import com.webrtc_phone.android.data.realtime.SocketState

private data class TabSpec(val key: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val socket by vm.socketState.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()

    val tabs = buildList {
        add(TabSpec("devices", "设备", Icons.Outlined.PhoneAndroid, Icons.Filled.PhoneAndroid))
        if (vm.session.isAdmin) add(TabSpec("manage", "管理", Icons.Outlined.AdminPanelSettings, Icons.Filled.AdminPanelSettings))
        add(TabSpec("me", "我的", Icons.Outlined.Person, Icons.Filled.Person))
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val nav: (String) -> Unit = { route ->
        if (route == "tab:manage") tab = tabs.indexOfFirst { it.key == "manage" }.coerceAtLeast(0) else navigate(route)
    }

    var actionFor by remember { mutableStateOf<Device?>(null) }
    var cameraFor by remember { mutableStateOf<Device?>(null) }
    var tagsFor by remember { mutableStateOf<Device?>(null) }
    var leaseFor by remember { mutableStateOf<Device?>(null) }
    val directState by vm.directConnect.collectAsStateWithLifecycle()

    // 页面可见时才保持实时连接/轮询；投屏期间 MainActivity 不可见，自动让出带宽
    LifecycleStartEffect(Unit) {
        vm.setActive(true)
        onStopOrDispose { vm.setActive(false) }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) { vm.directLaunch.collect { StreamLauncher.launch(context, it) } }

    fun launch(t: StreamTarget) = StreamLauncher.launch(context, t)
    fun open(d: Device) {
        if (!d.online) {
            actionFor = d
            return
        }
        launch(vm.targetFor(d))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tabs.getOrNull(tab)?.key) {
                            "manage" -> "管理中心"
                            "me" -> "我的"
                            else -> androidx.compose.ui.res.stringResource(com.webrtc_phone.android.R.string.app_title)
                        }
                    )
                },
                actions = {
                    if (tabs.getOrNull(tab)?.key == "devices") {
                        IconButton(onClick = vm::openDirectConnect) {
                            Icon(Icons.Outlined.PhonelinkSetup, contentDescription = "单机直连")
                        }
                        StatusDot(
                            when (socket) {
                                SocketState.CONNECTED -> AppColors.Online
                                SocketState.IDLE -> AppColors.Offline
                                else -> AppColors.Warning
                            },
                            Modifier.padding(end = 20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (tabs.size > 1) NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(if (tab == i) t.selectedIcon else t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tabs.getOrNull(tab)?.key ?: "devices") {
                "devices" -> DevicesTab(vm, onOpen = ::open, onMore = { actionFor = it })
                "manage" -> ManageTab(vm, nav)
                "me" -> MeTab(vm, nav)
            }
        }
    }

    actionFor?.let { d ->
        DeviceActionSheet(
            device = d,
            isAdmin = vm.session.isAdmin,
            canShare = vm.session.isAdmin && me?.policy?.forbidShare != true,
            onDismiss = { actionFor = null },
            onOpen = { launch(vm.targetFor(d)) },
            onOpenWs = { launch(vm.targetFor(d, transport = com.webrtc_phone.android.rtc.StreamTransport.WEBSOCKET)) },
            onCamera = { cameraFor = d },
            onShare = { navigate(Routes.shares(d.id, create = true)) },
            onTags = { tagsFor = d },
            onSetAlias = { newAlias -> vm.setDeviceAlias(d.id, newAlias) },
            onLease = { leaseFor = d },
            onDelete = { vm.deleteDevice(d) },
        )
    }
    leaseFor?.let { d ->
        DeviceLeaseSheet(
            device = d,
            onDismiss = { leaseFor = null },
            fetchLease = { vm.getDeviceLease(d.id) },
            fetchUsers = { vm.getUsers() },
            onCreateLease = { username, dur, note -> vm.createDeviceLease(username, d.id, dur, note) },
            onExtendLease = { leaseId, dur -> vm.extendDeviceLease(leaseId, dur) },
            onRevokeLease = { leaseId -> vm.revokeDeviceLease(leaseId) }
        )
    }
    cameraFor?.let { d ->
        CameraPickerSheet(d, onDismiss = { cameraFor = null }, onStart = { p -> launch(vm.targetFor(d, p)) })
    }
    tagsFor?.let { d ->
        DeviceTagSheet(
            deviceId = d.id,
            tags = tags.tags,
            selected = tags.deviceTags[d.id].orEmpty(),
            onDismiss = { tagsFor = null },
            onManageTags = { navigate(Routes.TAGS) },
            onSave = { vm.setDeviceTags(d.id, it) },
        )
    }
    if (directState.open) {
        DirectConnectDialog(
            state = directState,
            onEdit = vm::editDirectConnect,
            onPickRecent = vm::pickRecentDirect,
            onConnect = vm::connectDirect,
            onDismiss = vm::closeDirectConnect,
        )
    }
    directState.untrusted?.let {
        CertificateTrustDialog(it, onTrust = vm::trustDirectCertificate, onDismiss = vm::dismissDirectCertificate)
    }
}

@Composable
private fun DirectConnectDialog(
    state: DirectConnectUiState,
    onEdit: ((DirectConnectUiState) -> DirectConnectUiState) -> Unit,
    onPickRecent: (com.webrtc_phone.android.data.auth.SessionRepository.RecentDirectHost) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    var recentMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!state.loading) onDismiss() },
        title = { Text("手机单机直连") },
        text = {
            Column {
                Text("无需信令服务器，直接输入目标手机“独立单机模式”的地址与端口。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Box {
                    OutlinedTextField(
                        value = state.address,
                        onValueChange = { v -> onEdit { it.copy(address = v.trim()) } },
                        label = { Text("目标地址") },
                        placeholder = { Text("192.168.1.20:8443") },
                        singleLine = true,
                        enabled = !state.loading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        trailingIcon = if (state.recent.isNotEmpty()) {
                            { IconButton(onClick = { recentMenu = true }) { Icon(Icons.Outlined.History, "最近直连") } }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(expanded = recentMenu, onDismissRequest = { recentMenu = false }) {
                        state.recent.forEach { r ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(ServerEndpoint.parse(r.url)?.display ?: r.url)
                                        if (r.label.isNotBlank()) {
                                            Text(r.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                onClick = { recentMenu = false; onPickRecent(r) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                PasswordField(
                    value = state.password,
                    onValueChange = { v -> onEdit { it.copy(password = v) } },
                    label = "直连密码（无密码留空）",
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                    onImeAction = onConnect,
                )
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConnect, enabled = !state.loading) {
                if (state.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("立即连接")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.loading) { Text("取消") }
        },
    )
}
