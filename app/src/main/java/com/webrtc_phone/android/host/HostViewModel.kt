package com.webrtc_phone.android.host

import android.app.Application
import com.webrtc_phone.android.core.net.ServerEndpoint
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.net.Inet4Address
import java.net.NetworkInterface

enum class HostStatus {
    STOPPED,
    STARTING,
    RUNNING
}

data class HostUiState(
    val status: HostStatus = HostStatus.STOPPED,
    val agentPid: String = "",
    val serviceRunning: Boolean = false,
    val hasAssets: Boolean = true,
    val rootStatus: AgentController.RootStatus = AgentController.RootStatus.NOT_AVAILABLE,
    val shizukuStatus: AgentController.ShizukuStatus = AgentController.ShizukuStatus.NOT_RUNNING,
    val prefs: HostPreferences = HostPreferences(),
    val logs: String = "加载日志中...",
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val localIp: String = "127.0.0.1",
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: String? = null
)

class HostViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(
        HostUiState(
            hasAssets = AgentController.hasAssets(app),
            prefs = HostConfig.load(app),
            localIp = getLocalIpAddress()
        )
    )
    val state: StateFlow<HostUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        refreshState()
        startPolling()
        viewModelScope.launch {
            ProjectionStandbyService.state.collect {
                if (_state.value.prefs.runMode == RunMode.PROJECTION) pollInternal()
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                pollInternal()
                delay(3000)
            }
        }
    }

    private fun pollInternal() {
        val app = getApplication<Application>()
        if (_state.value.prefs.runMode == RunMode.PROJECTION) {
            val projection = ProjectionStandbyService.state.value
            _state.update {
                if (it.prefs.runMode != RunMode.PROJECTION) it else it.copy(
                    status = when {
                        projection.online -> HostStatus.RUNNING
                        projection.active -> HostStatus.STARTING
                        else -> HostStatus.STOPPED
                    },
                    serviceRunning = projection.active,
                    agentPid = "",
                    logs = projection.message.ifBlank { "开启在线待协助后，可从管理台发起请求" }
                )
            }
            return
        }
        val useShizuku = _state.value.prefs.runMode == RunMode.SHIZUKU
        // 进入页面时只查询已建立的 Root 会话，避免切换系统录屏前弹出 su 授权。
        val canQueryAgent = useShizuku || AgentHostService.isServiceRunning || AgentController.RootShell.isAlive()
        val pid = if (canQueryAgent) AgentController.isAgentRunning(useShizuku) else ""
        val logs = if (canQueryAgent) AgentController.readLogsTail(useShizuku) else "启动 Root 被控端后显示日志"
        val root = if (!useShizuku && AgentController.RootShell.isAlive()) {
            AgentController.checkRootStatus()
        } else _state.value.rootStatus
        val shizuku = AgentController.checkShizukuStatus()
        val hasAssets = AgentController.hasAssets(app)

        val newStatus = when {
            pid.isNotEmpty() -> HostStatus.RUNNING
            AgentHostService.isServiceRunning || _state.value.isBusy -> HostStatus.STARTING
            else -> HostStatus.STOPPED
        }

        _state.update {
            if (it.prefs.runMode == RunMode.PROJECTION) it else it.copy(
                status = newStatus,
                agentPid = pid,
                serviceRunning = AgentHostService.isServiceRunning,
                logs = logs,
                rootStatus = root,
                shizukuStatus = shizuku,
                hasAssets = hasAssets,
                localIp = getLocalIpAddress()
            )
        }
    }

    fun refreshState() {
        viewModelScope.launch(Dispatchers.IO) {
            pollInternal()
        }
    }

    fun updatePrefs(transform: (HostPreferences) -> HostPreferences) {
        if (AgentHostService.isServiceRunning || ProjectionStandbyService.state.value.active || _state.value.isBusy) return
        val previous = _state.value.prefs
        val requested = transform(previous)
        val updated = requested.copy(autoStart = requested.autoStart && requested.runMode == RunMode.ROOT)
        HostConfig.save(getApplication(), updated)
        _state.update { it.copy(prefs = updated) }
        if (updated.runMode != previous.runMode) refreshState()
    }

    fun fillSignalingUrl(url: String) {
        if (url.isBlank()) return
        val normalized = if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            if (url.startsWith("https://")) url.replaceFirst("https://", "wss://")
            else if (url.startsWith("http://")) url.replaceFirst("http://", "ws://")
            else "wss://$url"
        } else url
        updatePrefs { it.copy(signalingUrl = normalized) }
    }

    fun requestShizukuPermission() {
        try {
            if (AgentController.checkShizukuStatus() == AgentController.ShizukuStatus.RUNNING_WAITING_PERMISSION) {
                Shizuku.requestPermission(2001)
            } else refreshState()
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = "请求 Shizuku 授权失败: ${e.message}") }
        }
    }

    fun startProjectionStandby() {
        if (_state.value.isBusy || AgentHostService.isServiceRunning || ProjectionStandbyService.state.value.active) return
        if (ServerEndpoint.parse(_state.value.prefs.signalingUrl) == null) {
            _state.update { it.copy(errorMessage = "请填写有效的信令服务器地址") }
            return
        }
        _state.update { it.copy(errorMessage = null) }
        ProjectionStandbyService.start(getApplication())
    }

    fun startHost() {
        if (_state.value.isBusy || _state.value.prefs.runMode == RunMode.PROJECTION || ProjectionStandbyService.state.value.active) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isBusy = true, errorMessage = null) }
            val app = getApplication<Application>()
            val currentPrefs = _state.value.prefs
            val useShizuku = currentPrefs.runMode == RunMode.SHIZUKU

            if (!AgentController.hasAssets(app)) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = "当前版本缺少受控端内置组件（cloudphone-agent）。如需被控端功能，请使用包含 Agent 的完整构建版本。"
                    )
                }
                return@launch
            }

            if (currentPrefs.signalingUrl.isBlank()) {
                _state.update { it.copy(isBusy = false, errorMessage = "请填写外部信令服务器地址") }
                return@launch
            }
            // 检查权限
            if (useShizuku) {
                val shizukuStatus = AgentController.checkShizukuStatus()
                if (shizukuStatus == AgentController.ShizukuStatus.NOT_RUNNING) {
                    _state.update {
                        it.copy(isBusy = false, errorMessage = "Shizuku 服务未运行，请先打开 Shizuku 应用激活服务。")
                    }
                    return@launch
                }
                if (shizukuStatus == AgentController.ShizukuStatus.RUNNING_WAITING_PERMISSION) {
                    _state.update {
                        it.copy(isBusy = false, errorMessage = "请先授予 Shizuku 权限后再启动。")
                    }
                    return@launch
                }
            } else {
                val rootStatus = AgentController.checkRootStatus()
                _state.update { it.copy(rootStatus = rootStatus) }
                if (rootStatus != AgentController.RootStatus.GRANTED) {
                    _state.update {
                        it.copy(isBusy = false, errorMessage = "未获得 Root 权限。请在 Root 管理器（Magisk/KernelSU）中授予本应用超级用户权限。")
                    }
                    return@launch
                }
            }

            try {
                // 开启前台服务并拉起守护
                AgentHostService.start(app)
                // 服务启动与 Agent 就绪是不同状态；只在观察到 PID 后报告进程启动。
                var ready = false
                repeat(15) {
                    if (!ready) {
                        delay(1000)
                        pollInternal()
                        ready = _state.value.agentPid.isNotEmpty()
                    }
                }
                _state.update { it.copy(
                    isBusy = false,
                    toastMessage = if (ready) "被控端进程已启动" else null,
                    errorMessage = if (ready) null else AgentHostService.lastError
                        ?: "Agent 尚未启动，后台正在重试。请检查日志，或点击停止受控端。"
                ) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = "启动失败: ${e.message}"
                    )
                }
            }
        }
    }

    fun stopHost() {
        if (_state.value.isBusy) return
        if (_state.value.prefs.runMode == RunMode.PROJECTION) {
            ProjectionStandbyService.stop(getApplication())
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isBusy = true) }
            val app = getApplication<Application>()
            try {
                if (AgentHostService.isServiceRunning) {
                    AgentHostService.stop(app)
                    repeat(15) { if (AgentHostService.isServiceRunning) delay(1000) }
                    check(!AgentHostService.isServiceRunning) { "停止超时，请稍后重试" }
                } else {
                    AgentController.stopAgent(_state.value.prefs.runMode == RunMode.SHIZUKU)
                }
                pollInternal()
                check(_state.value.agentPid.isEmpty()) { "Agent 进程仍在运行，请检查权限和日志" }
                _state.update { it.copy(isBusy = false, status = HostStatus.STOPPED, toastMessage = "被控端已停止") }
            } catch (e: Exception) {
                _state.update { it.copy(isBusy = false, errorMessage = "停止失败: ${e.message}") }
            }
        }
    }

    fun clearToast() {
        _state.update { it.copy(toastMessage = null) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun getLocalIpAddress(): String {
        return try {
            val en = NetworkInterface.getNetworkInterfaces()
            var candidate = "127.0.0.1"
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val addr = enumIpAddr.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: ""
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                        if (candidate == "127.0.0.1" && ip.isNotBlank()) {
                            candidate = ip
                        }
                    }
                }
            }
            candidate
        } catch (_: Throwable) {
            "127.0.0.1"
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}
