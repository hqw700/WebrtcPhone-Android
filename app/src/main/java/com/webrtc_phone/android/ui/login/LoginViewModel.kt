package com.webrtc_phone.android.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webrtc_phone.android.AppContainer
import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.core.net.UntrustedCertificateInfo
import com.webrtc_phone.android.data.auth.SessionRepository
import com.webrtc_phone.android.data.model.CardRedeemResult
import com.webrtc_phone.android.rtc.StreamAuth
import com.webrtc_phone.android.rtc.StreamTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val cardCode: String = "",
    val cardPassword: String = "",
    val cardNeedsPassword: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val serverError: String? = null,
    val untrusted: UntrustedCertificateInfo? = null,
    val recent: List<SessionRepository.RecentServer> = emptyList(),
)

class LoginViewModel(private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        c.sessions.recentServers().let { recent ->
            LoginUiState(
                server = recent.firstOrNull()?.url?.let { ServerEndpoint.parse(it)?.display } ?: "",
                username = recent.firstOrNull()?.username.orEmpty(),
                recent = recent,
                error = c.sessions.logoutReason.value,
            )
        }
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** 卡密兑换成功 → 直接进入投屏 */
    private val _guestTarget = MutableStateFlow<StreamTarget?>(null)
    val guestTarget: StateFlow<StreamTarget?> = _guestTarget.asStateFlow()

    // 证书确认后需要重放的动作
    private var pendingAction: (() -> Unit)? = null

    init {
        c.sessions.consumeLogoutReason()
    }

    fun edit(transform: (LoginUiState) -> LoginUiState) =
        _state.update { transform(it).copy(error = null, serverError = null) }

    fun pickRecent(r: SessionRepository.RecentServer) = edit {
        it.copy(server = ServerEndpoint.parse(r.url)?.display ?: r.url, username = r.username, password = "")
    }

    fun forgetRecent(r: SessionRepository.RecentServer) {
        c.sessions.forgetServer(r.url)
        _state.update { it.copy(recent = c.sessions.recentServers()) }
    }

    private fun endpointOrError(): ServerEndpoint? {
        val ep = ServerEndpoint.parse(_state.value.server)
        if (ep == null) _state.update { it.copy(serverError = "请输入有效的服务器地址，如 192.168.1.10:8443") }
        return ep
    }

    fun login() {
        val s = _state.value
        val ep = endpointOrError() ?: return
        if (s.username.isBlank() || s.password.isEmpty()) {
            _state.update { it.copy(error = "请输入用户名和密码") }
            return
        }
        run(ep, ::login) {
            c.sessions.login(ep, s.username.trim(), s.password)
            _state.update { it.copy(password = "") }
        }
    }

    fun redeemCard() {
        val s = _state.value
        val ep = endpointOrError() ?: return
        if (s.cardCode.isBlank()) {
            _state.update { it.copy(error = "请输入卡密") }
            return
        }
        run(ep, ::redeemCard) {
            try {
                val r = c.sessions.anonymousApi(ep).redeemCard(s.cardCode, s.cardPassword)
                c.sessions.rememberServer(ep, s.username)
                _guestTarget.value = targetFor(ep, r, s.cardPassword)
            } catch (e: ApiException.Business) {
                when (e.code) {
                    401 -> _state.update {
                        it.copy(
                            cardNeedsPassword = true,
                            error = if (s.cardPassword.isEmpty()) "该卡密需要访问密码" else "访问密码错误"
                        )
                    }
                    404 -> _state.update { it.copy(error = "卡密不存在或已失效") }
                    else -> throw e
                }
            }
        }
    }

    private fun targetFor(ep: ServerEndpoint, r: CardRedeemResult, pwd: String) = StreamTarget(
        endpoint = ep,
        deviceId = r.deviceId,
        deviceName = r.deviceName,
        auth = StreamAuth.Share(r.token, pwd),
        accessMode = r.accessMode,
        allowClipboard = r.allowClipboard,
        allowFileTx = r.allowFileTx,
        allowTerminal = false,
        settings = c.settings.stream.value,
    )

    fun consumeGuestTarget() {
        _guestTarget.value = null
    }

    fun trustCertificate() {
        val info = _state.value.untrusted ?: return
        c.pinStore.pin(info.endpointKey, info)
        ServerEndpoint.parse(_state.value.server)?.let { c.httpFactory.invalidate(it) }
        _state.update { it.copy(untrusted = null) }
        pendingAction?.invoke()
        pendingAction = null
    }

    fun dismissCertificate() {
        pendingAction = null
        _state.update { it.copy(untrusted = null, error = "已取消：服务器证书未被信任") }
    }

    private fun run(ep: ServerEndpoint, retry: () -> Unit, block: suspend () -> Unit) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiException.UntrustedCertificate) {
                pendingAction = retry
                _state.update { it.copy(untrusted = e.info) }
            } catch (e: ApiException) {
                val msg = if (!ep.secure && e is ApiException.Network) "${e.message}（当前为 HTTP 明文连接）" else e.message
                _state.update { it.copy(error = msg) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }
}
