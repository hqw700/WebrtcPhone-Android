package com.webrtc_phone.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webrtc_phone.android.AppContainer
import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.core.net.UntrustedCertificateInfo
import com.webrtc_phone.android.data.auth.SessionRepository
import com.webrtc_phone.android.data.model.AuthSession
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.TagsPayload
import com.webrtc_phone.android.data.repo.DeviceRepository
import com.webrtc_phone.android.rtc.CameraParams
import com.webrtc_phone.android.rtc.StreamAuth
import com.webrtc_phone.android.rtc.StreamMode
import com.webrtc_phone.android.rtc.StreamTarget
import com.webrtc_phone.android.rtc.StreamTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class StatusFilter(val label: String) { ALL("全部"), ONLINE("在线"), IN_USE("使用中"), OFFLINE("离线") }

data class DeviceFilter(
    val query: String = "",
    val status: StatusFilter = StatusFilter.ALL,
    val tagIds: Set<String> = emptySet(),
) {
    /**
     * 纯函数过滤判断：状态 + 标签筛选 + 搜索（命中 设备 ID / 型号 / 标签名）。
     * 抽出来便于单测，UI 的 combine 直接调用。
     */
    fun matches(d: Device, tags: TagsPayload): Boolean {
        val statusOk = when (status) {
            StatusFilter.ALL -> true
            StatusFilter.ONLINE -> d.online
            StatusFilter.IN_USE -> d.online && d.inUse
            StatusFilter.OFFLINE -> !d.online
        }
        if (!statusOk) return false
        val devTagIds = tags.deviceTags[d.id].orEmpty()
        if (tagIds.isNotEmpty() && devTagIds.none { it in tagIds }) return false
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return d.id.lowercase().contains(q) ||
            d.alias.lowercase().contains(q) ||
            d.info.title.lowercase().contains(q) ||
            devTagIds.any { id -> tags.tags.firstOrNull { it.id == id }?.name?.lowercase()?.contains(q) == true }
    }
}

data class DirectConnectUiState(
    val open: Boolean = false,
    val address: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val untrusted: UntrustedCertificateInfo? = null,
    val recent: List<SessionRepository.RecentDirectHost> = emptyList(),
)

/** 首页（设备 / 管理 / 我的）共享的会话级 ViewModel */
class HomeViewModel(private val c: AppContainer, val session: AuthSession) : ViewModel() {

    val repo: DeviceRepository = c.userScope()!!.devices
    val quickTexts = c.userScope()!!.quickTexts
    val endpoint: ServerEndpoint = ServerEndpoint.parse(session.serverUrl)!!

    val devices = repo.devices
    val snapshots = repo.snapshots
    val tags = repo.tags
    val me = repo.me
    val loading = repo.loading
    val error = repo.error
    val socketState = repo.socketState
    val appSettings = c.settings.app

    private val _filter = MutableStateFlow(DeviceFilter())
    val filter: StateFlow<DeviceFilter> = _filter.asStateFlow()

    val filtered: StateFlow<List<Device>> = combine(devices, _filter, tags) { list, f, t ->
        list.filter { f.matches(it, t) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _directConnect = MutableStateFlow(DirectConnectUiState())
    val directConnect: StateFlow<DirectConnectUiState> = _directConnect.asStateFlow()

    private val _directLaunch = MutableSharedFlow<StreamTarget>(extraBufferCapacity = 4)
    val directLaunch = _directLaunch.asSharedFlow()

    fun openDirectConnect() {
        val recent = c.sessions.recentDirectHosts()
        val last = recent.firstOrNull()
        val addr = last?.url?.let { ServerEndpoint.parse(it)?.display }.orEmpty()
        val pwd = last?.let { c.sessions.savedDirectPassword(it.url) }.orEmpty()
        _directConnect.value = DirectConnectUiState(open = true, address = addr, password = pwd, recent = recent)
    }

    fun closeDirectConnect() {
        _directConnect.value = DirectConnectUiState(open = false)
    }

    fun editDirectConnect(transform: (DirectConnectUiState) -> DirectConnectUiState) {
        _directConnect.value = transform(_directConnect.value).copy(error = null)
    }

    fun pickRecentDirect(r: SessionRepository.RecentDirectHost) {
        _directConnect.value = _directConnect.value.copy(
            address = ServerEndpoint.parse(r.url)?.display ?: r.url,
            password = c.sessions.savedDirectPassword(r.url).orEmpty(),
            error = null,
        )
    }

    fun connectDirect() {
        val s = _directConnect.value
        val ep = ServerEndpoint.parse(s.address)
        if (ep == null) {
            _directConnect.value = s.copy(error = "请输入有效的手机直连地址，如 192.168.1.20:8443")
            return
        }
        if (s.loading) return
        _directConnect.value = s.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = c.sessions.connectStandalone(ep, s.password)
                if (s.password.isNotEmpty()) c.sessions.saveDirectPassword(ep.serialize(), s.password)
                val target = StreamTarget(
                    endpoint = ep,
                    deviceId = res.device.id,
                    deviceName = res.device.displayName,
                    auth = StreamAuth.User(res.token),
                    mode = StreamMode.Screen,
                    settings = c.settings.stream.value,
                    transport = StreamTransport.WEBRTC,
                    displayHint = res.device.info.primaryDisplay?.let { it.width to it.height },
                    standalone = true,
                    initialDeviceInfo = res.device.info,
                )
                withContext(Dispatchers.Main) {
                    _directConnect.value = DirectConnectUiState(open = false)
                    _directLaunch.tryEmit(target)
                }
            } catch (e: ApiException.UntrustedCertificate) {
                withContext(Dispatchers.Main) {
                    _directConnect.value = _directConnect.value.copy(loading = false, untrusted = e.info)
                }
            } catch (e: ApiException) {
                if (e is ApiException.Unauthorized) c.sessions.clearDirectPassword(ep.serialize())
                withContext(Dispatchers.Main) {
                    _directConnect.value = _directConnect.value.copy(loading = false, error = e.message ?: "直连失败")
                }
            }
        }
    }

    fun trustDirectCertificate() {
        val info = _directConnect.value.untrusted ?: return
        viewModelScope.launch(Dispatchers.IO) {
            c.pinStore.pin(info.endpointKey, info)
            ServerEndpoint.parse(_directConnect.value.address)?.let { c.httpFactory.invalidate(it) }
            withContext(Dispatchers.Main) {
                _directConnect.value = _directConnect.value.copy(untrusted = null)
                connectDirect()
            }
        }
    }

    fun dismissDirectCertificate() {
        _directConnect.value = _directConnect.value.copy(untrusted = null, error = "已取消：证书未被信任")
    }

    fun setFilter(transform: (DeviceFilter) -> DeviceFilter) {
        _filter.value = transform(_filter.value)
    }

    fun setActive(active: Boolean) = repo.setActive(active)

    fun refresh() = viewModelScope.launch {
        repo.refresh()
        repo.loadMeta()
    }

    fun setGridColumns(n: Int) = c.settings.updateApp { it.copy(gridColumns = n) }

    fun targetFor(d: Device, camera: CameraParams? = null, transport: com.webrtc_phone.android.rtc.StreamTransport = com.webrtc_phone.android.rtc.StreamTransport.WEBRTC): StreamTarget {
        val policy = me.value?.policy
        return StreamTarget(
            endpoint = endpoint,
            deviceId = d.id,
            deviceName = d.displayName,
            auth = StreamAuth.User(session.token),
            allowFileTx = policy?.forbidFilePush != true,
            allowTerminal = policy?.forbidTerminal != true,
            mode = if (camera != null) StreamMode.Camera(camera) else StreamMode.Screen,
            settings = c.settings.stream.value,
            transport = transport,
            initialDeviceInfo = d.info,
            projectionOnDemand = d.info.projectionOnDemand,
            displayHint = d.info.primaryDisplay?.let { it.width to it.height },
        )
    }

    fun deleteDevice(d: Device) = launchOp("已删除 ${d.id}") { repo.deleteDevice(d.id) }

    fun setDeviceAlias(deviceId: String, alias: String) = launchOp("名称已更新") { repo.setDeviceAlias(deviceId, alias) }

    fun setDeviceTags(deviceId: String, ids: List<String>) = launchOp("标签已更新") { repo.setDeviceTags(deviceId, ids) }

    // ---------- 设备开租与租约管理快捷支持 ----------

    suspend fun getDeviceLease(deviceId: String): com.webrtc_phone.android.data.model.AdminLease? {
        val api = c.sessions.api() ?: return null
        val leases = runCatching { api.adminLeases(device = deviceId, status = "active") }.getOrNull()
        return leases?.firstOrNull()
    }

    suspend fun getUsers(): List<com.webrtc_phone.android.data.model.AdminUser> {
        val api = c.sessions.api() ?: return emptyList()
        return runCatching { api.users() }.getOrDefault(emptyList())
    }

    fun createDeviceLease(username: String, deviceId: String, durationSeconds: Long, note: String) =
        launchOp("已为设备 $deviceId 开通租约") {
            val api = c.sessions.api() ?: return@launchOp
            api.createLease(com.webrtc_phone.android.data.model.LeaseCreateRequest(username, deviceId, durationSeconds, note))
            repo.refresh()
        }

    fun extendDeviceLease(leaseId: String, durationSeconds: Long) =
        launchOp("租约已展期") {
            val api = c.sessions.api() ?: return@launchOp
            api.extendLease(leaseId, durationSeconds)
            repo.refresh()
        }

    fun revokeDeviceLease(leaseId: String) =
        launchOp("租约已收回") {
            val api = c.sessions.api() ?: return@launchOp
            api.revokeLease(leaseId)
            repo.refresh()
        }

    fun logout() = viewModelScope.launch {
        c.streamSessions.close()
        repo.close()
        c.sessions.logout()
    }

    fun launchOp(success: String? = null, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            success?.let { _messages.tryEmit(it) }
        } catch (e: ApiException) {
            _messages.tryEmit(e.message ?: "操作失败")
        }
    }

    fun toast(msg: String) {
        _messages.tryEmit(msg)
    }

    override fun onCleared() {
        repo.setActive(false)
    }
}
