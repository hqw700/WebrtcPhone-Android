package com.webrtc_phone.android.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webrtc_phone.android.AppContainer
import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.core.net.UntrustedCertificateInfo
import com.webrtc_phone.android.data.auth.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LoginUiState(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val serverError: String? = null,
    val untrusted: UntrustedCertificateInfo? = null,
    val recent: List<SessionRepository.RecentServer> = emptyList(),
)

class LoginViewModel(private val c: AppContainer) : ViewModel() {

    private val repo = c.sessions

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    // 证书确认后需要重放的动作
    private var pendingAction: (() -> Unit)? = null

    init {
        repo.consumeLogoutReason()
    }

    private fun initialState(): LoginUiState {
        val recent = repo.recentServers()
        val last = recent.firstOrNull()
        val remember = repo.rememberPassword
        return LoginUiState(
            server = last?.url?.let { ServerEndpoint.parse(it)?.display }.orEmpty(),
            username = last?.username.orEmpty(),
            password = if (remember && last != null) repo.savedPassword(last.url, last.username).orEmpty() else "",
            rememberPassword = remember,
            recent = recent,
            error = repo.logoutReason.value,
        )
    }

    fun edit(transform: (LoginUiState) -> LoginUiState) =
        _state.update { transform(it).copy(error = null, serverError = null) }

    fun setRememberPassword(on: Boolean) {
        repo.rememberPassword = on
        if (!on) {
            // 取消勾选立即删除当前账号已保存的密码
            val s = _state.value
            ServerEndpoint.parse(s.server)?.let { repo.clearPassword(it.serialize(), s.username.trim()) }
        }
        _state.update { it.copy(rememberPassword = on) }
    }

    fun pickRecent(r: SessionRepository.RecentServer) = edit {
        it.copy(
            server = ServerEndpoint.parse(r.url)?.display ?: r.url,
            username = r.username,
            password = if (it.rememberPassword) repo.savedPassword(r.url, r.username).orEmpty() else "",
        )
    }

    fun forgetRecent(r: SessionRepository.RecentServer) {
        repo.forgetServer(r.url)
        _state.update { it.copy(recent = repo.recentServers()) }
    }

    fun login() {
        val s = _state.value
        val ep = ServerEndpoint.parse(s.server)
        if (ep == null) {
            _state.update { it.copy(serverError = "请输入有效的服务器地址，如 192.168.1.10:8443") }
            return
        }
        val username = s.username.trim()
        if (username.isEmpty() || s.password.isEmpty()) {
            _state.update { it.copy(error = "请输入用户名和密码") }
            return
        }
        if (s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.login(ep, username, s.password)
                if (s.rememberPassword) repo.savePassword(ep.serialize(), username, s.password)
                else repo.clearPassword(ep.serialize(), username)
            } catch (e: ApiException.UntrustedCertificate) {
                withContext(Dispatchers.Main) {
                    pendingAction = ::login
                    _state.update { it.copy(untrusted = e.info) }
                }
            } catch (e: ApiException) {
                if (e is ApiException.Unauthorized) repo.clearPassword(ep.serialize(), username)
                val msg = if (!ep.secure && e is ApiException.Network) "${e.message}（当前为 HTTP 明文连接）" else e.message
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(error = msg) }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(loading = false) }
                }
            }
        }
    }

    fun trustCertificate() {
        val info = _state.value.untrusted ?: return
        viewModelScope.launch(Dispatchers.IO) {
            c.pinStore.pin(info.endpointKey, info)
            ServerEndpoint.parse(_state.value.server)?.let { c.httpFactory.invalidate(it) }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(untrusted = null) }
                pendingAction?.invoke()
                pendingAction = null
            }
        }
    }

    fun dismissCertificate() {
        pendingAction = null
        _state.update { it.copy(untrusted = null, error = "已取消：服务器证书未被信任") }
    }
}
