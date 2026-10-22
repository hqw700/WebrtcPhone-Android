package com.webrtc_phone.android.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.data.api.CloudPhoneApi
import com.webrtc_phone.android.data.model.AdminLease
import com.webrtc_phone.android.data.model.AdminUser
import com.webrtc_phone.android.data.model.AdminUserCreate
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.LeaseCreateRequest
import com.webrtc_phone.android.data.model.Share
import com.webrtc_phone.android.data.model.ShareCreateRequest
import com.webrtc_phone.android.data.model.ShareCreated
import com.webrtc_phone.android.data.model.UserPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 列表页通用状态 */
data class ListState<T>(val items: List<T> = emptyList(), val loading: Boolean = false, val error: String? = null)

abstract class BaseListViewModel<T> : ViewModel() {
    protected val _state = MutableStateFlow(ListState<T>())
    val state: StateFlow<ListState<T>> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    protected abstract suspend fun fetch(): List<T>

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        try {
            _state.value = ListState(fetch())
        } catch (e: ApiException) {
            _state.value = _state.value.copy(loading = false, error = e.message)
        }
    }

    protected fun op(success: String?, reload: Boolean = true, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            success?.let { _messages.tryEmit(it) }
            if (reload) load()
        } catch (e: ApiException) {
            _messages.tryEmit(e.message ?: "操作失败")
        }
    }

    fun toast(msg: String) {
        _messages.tryEmit(msg)
    }
}

class SharesViewModel(private val api: CloudPhoneApi, val deviceFilter: String?) : BaseListViewModel<Share>() {

    private val _created = MutableStateFlow<ShareCreated?>(null)
    val created: StateFlow<ShareCreated?> = _created.asStateFlow()

    init {
        load()
    }

    override suspend fun fetch() = api.shares(deviceFilter)

    fun create(req: ShareCreateRequest) = op("分享已创建") { _created.value = api.createShare(req) }
    fun revoke(s: Share) = op("已撤销 ${s.cardCode}") { api.revokeShare(s.token) }
    fun extend(s: Share, seconds: Long) = op("已延长有效期") { api.extendShare(s.token, seconds) }
    fun dismissCreated() {
        _created.value = null
    }

    fun linkFor(token: String) = api.shareLink(token)
}

/** 用户管理与租约聚合状态 */
data class UsersUiState(
    val users: List<AdminUser> = emptyList(),
    val leases: List<AdminLease> = emptyList(),
    val devices: List<Device> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** 活跃租约按用户名索引 */
    val activeLeasesByUser: Map<String, List<AdminLease>> get() =
        leases.filter { it.active }.groupBy { it.username }

    /** 全部租约按用户名索引 */
    val allLeasesByUser: Map<String, List<AdminLease>> get() =
        leases.groupBy { it.username }

    /** 当前活跃租约所占用的设备 ID 集合 */
    val leasedDeviceIds: Set<String> get() =
        leases.filter { it.active }.map { it.deviceId }.toSet()

    /** 闲置设备（在线或离线，但当前没有活跃租约） */
    val idleDevices: List<Device> get() =
        devices.filter { it.id !in leasedDeviceIds }

    val totalUsers: Int get() = users.size
    val onlineUsers: Int get() = users.count { it.online }
    val adminUsers: Int get() = users.count { it.isAdmin }
    val totalActiveLeases: Int get() = leases.count { it.active }
}

class UsersViewModel(private val api: CloudPhoneApi) : ViewModel() {
    private val _state = MutableStateFlow(UsersUiState())
    val state: StateFlow<UsersUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        try {
            val uDeferred = async { api.users() }
            val lDeferred = async { api.adminLeases(status = "all") }
            val dDeferred = async { runCatching { api.devices() }.getOrDefault(emptyList()) }
            val users = uDeferred.await()
            val leases = lDeferred.await()
            val devices = dDeferred.await()
            _state.value = UsersUiState(
                users = users,
                leases = leases,
                devices = devices,
                loading = false,
                error = null
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, error = e.message)
        }
    }

    private fun op(success: String?, reload: Boolean = true, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            success?.let { _messages.tryEmit(it) }
            if (reload) load()
        } catch (e: Exception) {
            _messages.tryEmit(e.message ?: "操作失败")
        }
    }

    fun toast(msg: String) {
        _messages.tryEmit(msg)
    }

    // 用户操作
    fun create(req: AdminUserCreate) = op("已创建用户 ${req.username}") { api.createUser(req) }
    fun delete(u: AdminUser) = op("已删除 ${u.username}") { api.deleteUser(u.username) }
    fun updateNote(u: AdminUser, note: String) = op("备注已更新") { api.updateUserNote(u.username, note) }
    fun resetPassword(u: AdminUser, pwd: String) = op("密码已重置", reload = false) { api.resetUserPassword(u.username, pwd) }
    fun kick(u: AdminUser, deviceId: String = "") = op("已将 ${u.username} 踢下线") { api.kickUser(u.username, deviceId) }
    fun update(u: AdminUser, expireSeconds: Long, policy: UserPolicy) = op("已保存") { api.updateUser(u.username, expireSeconds, policy) }

    // 租约操作（支持给设备开租、展期、收回、改备注）
    fun createLease(username: String, deviceId: String, durationSeconds: Long, note: String) =
        op("已为 $username 开通设备 $deviceId 租约") {
            api.createLease(LeaseCreateRequest(username = username, deviceId = deviceId, durationSeconds = durationSeconds, note = note))
        }

    fun extendLease(lease: AdminLease, durationSeconds: Long) =
        op("已为租约延长有效时长") {
            api.extendLease(lease.leaseId, durationSeconds)
        }

    fun revokeLease(lease: AdminLease) =
        op("已收回设备 ${lease.deviceId} 租约") {
            api.revokeLease(lease.leaseId)
        }

    fun updateLeaseNote(lease: AdminLease, note: String) =
        op("租约备注已更新") {
            api.updateLeaseNote(lease.leaseId, note)
        }
}
