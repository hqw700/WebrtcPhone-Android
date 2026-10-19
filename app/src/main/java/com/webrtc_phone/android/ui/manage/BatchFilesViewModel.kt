package com.webrtc_phone.android.ui.manage

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.ServerFile
import com.webrtc_phone.android.data.model.TagsPayload
import com.webrtc_phone.android.data.repo.DeviceRepository
import com.webrtc_phone.android.data.repo.ServerFileRepository
import com.webrtc_phone.android.ui.stream.queryLocal
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 设备筛选范围 */
enum class DeviceScope(val label: String) { ONLINE("在线"), ALL("全部") }

/**
 * 管理页「批量文件分发」：上传文件到服务器盘，一次性下发/安装到多台设备（对齐 Web 端批量任务）。
 * 为应对大量文件/设备：文件与设备各自独立的搜索与筛选，选择状态与列表解耦。
 */
class BatchFilesViewModel(
    private val appContext: Context,
    private val repo: ServerFileRepository,
    private val deviceRepo: DeviceRepository,
) : ViewModel() {

    val loading = repo.loading
    val ops = repo.ops
    val task = repo.task
    val dispatching = repo.dispatching
    val tags: StateFlow<TagsPayload> = deviceRepo.tags

    // ---------- 文件：搜索 ----------
    private val _fileQuery = MutableStateFlow("")
    val fileQuery = _fileQuery.asStateFlow()
    fun setFileQuery(q: String) { _fileQuery.value = q }

    val files: StateFlow<List<ServerFile>> = combine(repo.files, _fileQuery) { list, q ->
        val k = q.trim().lowercase()
        if (k.isEmpty()) list else list.filter { it.name.lowercase().contains(k) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- 设备：搜索 + 状态 + 标签 ----------
    private val _devQuery = MutableStateFlow("")
    val devQuery = _devQuery.asStateFlow()
    fun setDevQuery(q: String) { _devQuery.value = q }

    private val _scope = MutableStateFlow(DeviceScope.ONLINE)
    val scope = _scope.asStateFlow()
    fun setScope(s: DeviceScope) { _scope.value = s }

    private val _tagFilter = MutableStateFlow<Set<String>>(emptySet())
    val tagFilter = _tagFilter.asStateFlow()
    fun toggleTagFilter(id: String) { _tagFilter.value = _tagFilter.value.let { if (id in it) it - id else it + id } }

    val allDevices = deviceRepo.devices

    val shownDevices: StateFlow<List<Device>> =
        combine(deviceRepo.devices, _devQuery, _scope, _tagFilter, deviceRepo.tags) { list, q, scope, tagF, tagsP ->
            val k = q.trim().lowercase()
            list.filter { d ->
                val scopeOk = when (scope) { DeviceScope.ONLINE -> d.online; DeviceScope.ALL -> true }
                val tagOk = tagF.isEmpty() || tagsP.deviceTags[d.id].orEmpty().any { it in tagF }
                val qOk = k.isEmpty() || d.id.lowercase().contains(k) || d.info.title.lowercase().contains(k)
                scopeOk && tagOk && qOk
            }.sortedWith(compareByDescending<Device> { it.online }.thenBy { it.id })
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- 选择 ----------
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected = _selected.asStateFlow()

    private val _picked = MutableStateFlow<ServerFile?>(null)
    val picked = _picked.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    init {
        refresh()
        viewModelScope.launch { runCatching { deviceRepo.refresh(showLoading = false) } }
    }

    fun refresh() = viewModelScope.launch {
        runCatching { repo.refresh() }.onFailure { toast((it as? ApiException)?.message ?: "刷新失败") }
        runCatching { deviceRepo.refresh(showLoading = false) }
    }

    fun pick(file: ServerFile?) { _picked.value = file }

    fun toggleDevice(id: String) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    /** 全选/取消全选「当前筛选结果」中的设备（只影响可见项，避免误选隐藏设备） */
    fun toggleSelectAllShown() {
        val shownIds = shownDevices.value.map { it.id }.toSet()
        val allSelected = shownIds.isNotEmpty() && shownIds.all { it in _selected.value }
        _selected.value = if (allSelected) _selected.value - shownIds else _selected.value + shownIds
    }

    fun clearSelection() { _selected.value = emptySet() }

    fun upload(uri: Uri, name: String, size: Long) = viewModelScope.launch {
        runCatching { repo.upload(uri, name, size) }
            .onSuccess { toast("已上传到服务器"); _picked.value = repo.files.value.firstOrNull { it.name == name } }
            .onFailure { toast((it as? ApiException)?.message ?: "上传失败") }
    }

    fun uploadFrom(uri: Uri) = viewModelScope.launch {
        val p = queryLocal(appContext, uri)
        upload(uri, p.name, p.size)
    }

    fun deleteServerFile(name: String) = viewModelScope.launch {
        runCatching { repo.delete(name) }
            .onSuccess { if (_picked.value?.name == name) _picked.value = null; toast("已删除") }
            .onFailure { toast((it as? ApiException)?.message ?: "删除失败") }
    }

    fun dispatch(destPath: String?) {
        val file = _picked.value ?: run { toast("请先选择文件"); return }
        val targets = _selected.value.toList()
        if (targets.isEmpty()) { toast("请选择至少一台设备"); return }
        viewModelScope.launch {
            runCatching { repo.dispatchBatch(file, targets, destPath) }
                .onFailure { toast((it as? ApiException)?.message ?: "下发失败") }
        }
    }

    fun clearTask() = repo.clearTask()

    private fun toast(m: String) { _messages.tryEmit(m) }
}
