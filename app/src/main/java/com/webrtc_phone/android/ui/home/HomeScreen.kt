package com.webrtc_phone.android.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.rtc.StreamTarget
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

    // 页面可见时才保持实时连接/轮询；投屏期间 MainActivity 不可见，自动让出带宽
    LifecycleStartEffect(Unit) {
        vm.setActive(true)
        onStopOrDispose { vm.setActive(false) }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

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
                            else -> "我的云手机"
                        }
                    )
                },
                actions = {
                    if (tabs.getOrNull(tab)?.key == "devices") {
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
            onDelete = { vm.deleteDevice(d) },
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
}
