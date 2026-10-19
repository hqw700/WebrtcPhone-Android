package com.webrtc_phone.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webrtc_phone.android.AppContainer
import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.data.model.AuthSession
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.TagsPayload
import com.webrtc_phone.android.data.repo.DeviceRepository
import com.webrtc_phone.android.rtc.CameraParams
import com.webrtc_phone.android.rtc.StreamAuth
import com.webrtc_phone.android.rtc.StreamMode
import com.webrtc_phone.android.rtc.StreamTarget
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
            d.info.title.lowercase().contains(q) ||
            devTagIds.any { id -> tags.tags.firstOrNull { it.id == id }?.name?.lowercase()?.contains(q) == true }
    }
}

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
            deviceName = d.info.title.ifBlank { d.id },
            auth = StreamAuth.User(session.token),
            allowFileTx = policy?.forbidFilePush != true,
            allowTerminal = policy?.forbidTerminal != true,
            mode = if (camera != null) StreamMode.Camera(camera) else StreamMode.Screen,
            settings = c.settings.stream.value,
            transport = transport,
            displayHint = d.info.primaryDisplay?.let { it.width to it.height },
        )
    }

    fun deleteDevice(d: Device) = launchOp("已删除 ${d.id}") { repo.deleteDevice(d.id) }

    fun setDeviceTags(deviceId: String, ids: List<String>) = launchOp("标签已更新") { repo.setDeviceTags(deviceId, ids) }

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
