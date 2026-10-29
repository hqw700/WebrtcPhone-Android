package com.webrtc_phone.android.host

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.ui.common.Pill
import com.webrtc_phone.android.ui.common.SectionCard
import com.webrtc_phone.android.ui.common.StatusDot
import com.webrtc_phone.android.ui.common.SwitchRow
import com.webrtc_phone.android.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HostScreen(
    vm: HostViewModel,
    currentServerUrl: String? = null,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    if (state.errorMessage != null) {
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            title = { Text("提示") },
            text = { Text(state.errorMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { vm.clearError() }) {
                    Text("确定")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本机受控端 (Host Agent)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshState() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // 缺失资产警告
            if (!state.hasAssets) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.Warning.copy(alpha = 0.15f)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.WarningAmber, null, tint = AppColors.Warning, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("未集成受控端内置组件", fontWeight = FontWeight.Bold, color = AppColors.Warning)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "当前安装包为精简客户端模式，未打包 cloudphone-agent 二进制。请使用包含受控组件的 Full 发行版。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 状态卡片
            HostStatusBanner(state)

            // 核心控制按钮
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                val isRunning = state.status == HostStatus.RUNNING || state.serviceRunning
                Button(
                    onClick = {
                        if (isRunning) vm.stopHost() else vm.startHost()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) AppColors.Danger else AppColors.Brand
                    ),
                    enabled = !state.isBusy
                ) {
                    if (state.isBusy) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (isRunning) "正在停止..." else "正在启动...")
                    } else {
                        Icon(
                            if (isRunning) Icons.Outlined.PowerSettingsNew else Icons.Outlined.PowerSettingsNew,
                            null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isRunning) "停止受控端 (Stop Agent)" else "启动受控端 (Start Agent)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // 连接与信令配置
            SectionCard(title = "连接与信令配置") {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = state.prefs.signalingUrl,
                        onValueChange = { vm.updatePrefs { p -> p.copy(signalingUrl = it) } },
                        label = { Text("信令服务器地址 (ws:// 或 wss://)") },
                        leadingIcon = { Icon(Icons.Outlined.Dns, null) },
                        enabled = !state.serviceRunning && !state.isBusy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如 wss://192.168.1.100:8443") }
                    )

                    if (!currentServerUrl.isNullOrBlank() && currentServerUrl != state.prefs.signalingUrl) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { vm.fillSignalingUrl(currentServerUrl) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Outlined.Check, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("填入当前登录的信令服务器: $currentServerUrl", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.prefs.deviceId,
                        onValueChange = { vm.updatePrefs { p -> p.copy(deviceId = it) } },
                        label = { Text("自定义设备 ID (空则使用系统默认)") },
                        leadingIcon = { Icon(Icons.Outlined.Info, null) },
                        enabled = !state.serviceRunning && !state.isBusy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.prefs.iceServers,
                        onValueChange = { vm.updatePrefs { p -> p.copy(iceServers = it) } },
                        label = { Text("ICE / STUN 穿透服务器 (可选)") },
                        leadingIcon = { Icon(Icons.Outlined.Hub, null) },
                        enabled = !state.serviceRunning && !state.isBusy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如 stun:stun.l.google.com:19302") }
                    )
                }
            }

            // 执行引擎与保活选项
            SectionCard(title = "运行引擎与守护") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("选择执行引擎", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            enabled = !state.serviceRunning && !state.isBusy,
                            selected = state.prefs.runMode == RunMode.ROOT,
                            onClick = { vm.updatePrefs { it.copy(runMode = RunMode.ROOT) } },
                            label = { Text("Root (su 提权)") },
                            leadingIcon = if (state.prefs.runMode == RunMode.ROOT) {
                                { Icon(Icons.Outlined.Check, null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppColors.Brand.copy(alpha = 0.2f),
                                selectedLabelColor = AppColors.Brand
                            )
                        )
                        FilterChip(
                            enabled = !state.serviceRunning && !state.isBusy,
                            selected = state.prefs.runMode == RunMode.SHIZUKU,
                            onClick = { vm.updatePrefs { it.copy(runMode = RunMode.SHIZUKU) } },
                            label = { Text("Shizuku (无线调试/免 Root)") },
                            leadingIcon = if (state.prefs.runMode == RunMode.SHIZUKU) {
                                { Icon(Icons.Outlined.Check, null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppColors.Brand.copy(alpha = 0.2f),
                                selectedLabelColor = AppColors.Brand
                            )
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (state.prefs.runMode) {
                            RunMode.ROOT -> "Root 模式：利用常驻 su 会话调用，需要设备已刷入 Magisk / KernelSU / APatch。"
                            RunMode.SHIZUKU -> "Shizuku 模式：通过 ADB 无线调试或已启动的 Shizuku 服务运行，无需解锁 Root。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (state.prefs.runMode == RunMode.SHIZUKU) {
                    TextButton(onClick = vm::requestShizukuPermission) {
                        Text("请求 Shizuku 授权")
                    }
                    Text(
                        "Shizuku 调试模式在重启后需要重新激活，不支持开机自启动。",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (state.prefs.runMode == RunMode.ROOT) SwitchRow(
                    title = "开机自启动（Root）",
                    enabled = !state.serviceRunning && !state.isBusy,
                    subtitle = "系统开机完成或本应用升级后自动拉起受控端服务",
                    checked = state.prefs.autoStart,
                    onChange = { vm.updatePrefs { p -> p.copy(autoStart = it) } }
                )
                SwitchRow(
                    title = "前台常驻与防休眠",
                    enabled = !state.serviceRunning && !state.isBusy,
                    subtitle = "显示常驻状态通知并持有 WakeLock，防止手机灭屏时被系统休眠杀死",
                    checked = state.prefs.keepAlive,
                    onChange = { vm.updatePrefs { p -> p.copy(keepAlive = it) } }
                )
            }

            // 终端实时日志
            SectionCard(title = "终端实时日志") {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Terminal, null, modifier = Modifier.size(18.dp), tint = AppColors.Brand)
                            Spacer(Modifier.width(6.dp))
                            Text("运行输出 (tail -n 40)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Row {
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(state.logs))
                                Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "复制日志", modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { vm.refreshState() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "刷新日志", modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 240.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0F141C)
                    ) {
                        SelectionContainer {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(10.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = state.logs.ifBlank { "暂无日志" },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFF81C784),
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostStatusBanner(state: HostUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dotColor = when (state.status) {
                        HostStatus.RUNNING -> AppColors.Online
                        HostStatus.STARTING -> AppColors.Warning
                        HostStatus.STOPPED -> AppColors.Danger
                    }
                    StatusDot(dotColor, size = 12)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when (state.status) {
                            HostStatus.RUNNING -> "正在受控运行中"
                            HostStatus.STARTING -> "正在启动中..."
                            HostStatus.STOPPED -> "未运行 (已停止)"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (state.agentPid.isNotEmpty()) {
                    Pill("PID: ${state.agentPid}", AppColors.Brand, filled = false)
                }
            }

            Spacer(Modifier.height(14.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoChip("📱 ${state.deviceModel}")
                InfoChip("🌐 ${state.localIp}")
                InfoChip("🤖 ${state.androidVersion}")
                val engineDesc = when (state.prefs.runMode) {
                    RunMode.ROOT -> if (state.rootStatus == AgentController.RootStatus.GRANTED) "Root: 已授权" else "Root: 未授权"
                    RunMode.SHIZUKU -> when (state.shizukuStatus) {
                        AgentController.ShizukuStatus.RUNNING_AND_GRANTED -> "Shizuku: 已激活"
                        AgentController.ShizukuStatus.RUNNING_WAITING_PERMISSION -> "Shizuku: 待授权"
                        AgentController.ShizukuStatus.NOT_RUNNING -> "Shizuku: 未运行"
                    }
                }
                InfoChip("⚡ $engineDesc")
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
