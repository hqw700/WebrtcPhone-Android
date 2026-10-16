package com.webrtc_phone.android.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.data.api.CloudPhoneApi
import com.webrtc_phone.android.data.model.AdminUser
import com.webrtc_phone.android.data.model.AdminUserCreate
import com.webrtc_phone.android.data.model.Share
import com.webrtc_phone.android.data.model.ShareCreateRequest
import com.webrtc_phone.android.data.model.ShareCreated
import com.webrtc_phone.android.data.model.UserPolicy
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

class UsersViewModel(private val api: CloudPhoneApi) : BaseListViewModel<AdminUser>() {
    init {
        load()
    }

    override suspend fun fetch() = api.users()

    fun create(req: AdminUserCreate) = op("已创建用户 ${req.username}") { api.createUser(req) }
    fun delete(u: AdminUser) = op("已删除 ${u.username}") { api.deleteUser(u.username) }
    fun updateNote(u: AdminUser, note: String) = op("备注已更新") { api.updateUserNote(u.username, note) }
    fun resetPassword(u: AdminUser, pwd: String) = op("密码已重置", reload = false) { api.resetUserPassword(u.username, pwd) }
    fun kick(u: AdminUser) = op("已将 ${u.username} 踢下线") { api.kickUser(u.username) }
    fun update(u: AdminUser, expireSeconds: Long, policy: UserPolicy) = op("已保存") { api.updateUser(u.username, expireSeconds, policy) }
}
