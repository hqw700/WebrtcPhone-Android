package com.webrtc_phone.android

import android.app.Application
import android.content.Context
import com.webrtc_phone.android.core.net.CertificatePinStore
import com.webrtc_phone.android.core.net.HttpClientFactory
import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.core.security.SecureStore
import com.webrtc_phone.android.data.auth.SessionRepository
import com.webrtc_phone.android.data.model.AuthSession
import com.webrtc_phone.android.data.realtime.DashboardSocket
import com.webrtc_phone.android.data.repo.DeviceRepository
import com.webrtc_phone.android.data.repo.QuickTextRepository
import com.webrtc_phone.android.data.settings.SettingsRepository
import com.webrtc_phone.android.rtc.RtcEngine
import com.webrtc_phone.android.rtc.SessionManager

/**
 * 手动依赖注入容器。业务对象分两级：
 * - 进程级（网络、设置、WebRTC 引擎、投屏会话）；
 * - 登录会话级（设备仓库、快捷文本），登录态变化时重建，避免跨账号数据串用。
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val secureStore = SecureStore(appContext)
    val pinStore = CertificatePinStore(appContext)
    val httpFactory = HttpClientFactory(pinStore)
    val settings = SettingsRepository(appContext)
    val sessions = SessionRepository(appContext, secureStore, httpFactory)
    val rtcEngine = RtcEngine(appContext)
    val streamSessions = SessionManager(appContext, rtcEngine, httpFactory)

    private var scoped: Pair<AuthSession, UserScope>? = null

    class UserScope(val devices: DeviceRepository, val quickTexts: QuickTextRepository)

    /** 当前登录会话的仓库；未登录返回 null */
    @Synchronized
    fun userScope(): UserScope? {
        val s = sessions.session.value
        if (s == null) {
            scoped?.second?.devices?.close()
            scoped = null
            return null
        }
        scoped?.let { (cs, scope) -> if (cs == s) return scope }
        scoped?.second?.devices?.close()
        val api = sessions.api() ?: return null
        val endpoint = ServerEndpoint.parse(s.serverUrl) ?: return null
        val socket = DashboardSocket(
            httpFactory, endpoint,
            tokenProvider = { sessions.session.value?.token },
            onUnauthorized = { sessions.expire("登录已过期，请重新登录") },
        )
        val scope = UserScope(DeviceRepository(s, api, socket), QuickTextRepository(api))
        scoped = s to scope
        return scope
    }
}

class WebrtcPhoneApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as WebrtcPhoneApp).container
