package com.webrtc_phone.android.host

import android.app.Application
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
import kotlinx.coroutines.withContext
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
        val useShizuku = _state.value.prefs.runMode == RunMode.SHIZUKU
        val pid = AgentController.isAgentRunning(useShizuku)
        val logs = AgentController.readLogsTail(useShizuku)
        val root = AgentController.checkRootStatus()
        val shizuku = AgentController.checkShizukuStatus()
        val hasAssets = AgentController.hasAssets(app)

        val newStatus = when {
            pid.isNotEmpty() -> HostStatus.RUNNING
            _state.value.isBusy -> HostStatus.STARTING
            else -> HostStatus.STOPPED
        }

        _state.update {
            it.copy(
                status = newStatus,
                agentPid = pid,
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
        val updated = transform(_state.value.prefs)
        HostConfig.save(getApplication(), updated)
        _state.update { it.copy(prefs = updated) }
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

    fun startHost() {
        if (_state.value.isBusy) return
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
                delay(1200)
                pollInternal()
                _state.update { it.copy(isBusy = false, toastMessage = "被控端已成功启动！") }
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
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isBusy = true) }
            val app = getApplication<Application>()
            AgentHostService.stop(app)
            val useShizuku = _state.value.prefs.runMode == RunMode.SHIZUKU
            AgentController.stopAgent(useShizuku)
            delay(1000)
            pollInternal()
            _state.update { it.copy(isBusy = false, toastMessage = "被控端已停止") }
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
