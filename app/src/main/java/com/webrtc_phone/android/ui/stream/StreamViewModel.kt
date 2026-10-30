package com.webrtc_phone.android.ui.stream

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webrtc_phone.android.AppContainer
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.QuickText
import com.webrtc_phone.android.data.model.TagsPayload
import com.webrtc_phone.android.data.repo.Snapshot
import com.webrtc_phone.android.data.settings.StreamSettings
import com.webrtc_phone.android.rtc.CameraParams
import com.webrtc_phone.android.rtc.ConnectionPhase
import com.webrtc_phone.android.rtc.RtcSession
import com.webrtc_phone.android.rtc.StreamAuth
import com.webrtc_phone.android.rtc.StreamMode
import com.webrtc_phone.android.rtc.StreamTarget
import com.webrtc_phone.android.rtc.channel.Keys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Panel { NONE, TOOLS, TEXT, FILES, TERMINAL, QUALITY, INFO, APPS }

/**
 * 投屏页状态持有者。连接本身由进程级 SessionManager 托管（页面重建不断线），
 * 这里只保存 UI 状态并把用户操作翻译成协议调用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamViewModel(private val c: AppContainer, initial: StreamTarget) : ViewModel() {

    private val manager = c.streamSessions
    val settings = c.settings
    val quickTexts = c.userScope()?.quickTexts

    var target by mutableStateOf(initial)
        private set

    val session: StateFlow<RtcSession?> = manager.session
    val retry = manager.retry

    // WS 模式会话（WebSocket H.264 中转）。与 RtcSession 二选一，由 target.transport 决定。
    private val _wsSession = MutableStateFlow<com.webrtc_phone.android.rtc.ws.WsStreamSession?>(null)
    val wsSession: StateFlow<com.webrtc_phone.android.rtc.ws.WsStreamSession?> = _wsSession.asStateFlow()

    val isWs: Boolean get() = target.isWs

    val phase: StateFlow<ConnectionPhase> =
        if (initial.isWs) _wsSession.flatMapLatest { it?.phase ?: flowOf(ConnectionPhase.Signaling) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionPhase.Signaling)
        else session.flatMapLatest { it?.phase ?: flowOf(ConnectionPhase.Closed) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionPhase.Signaling)
    val videoSize =
        if (initial.isWs) _wsSession.flatMapLatest { it?.videoSize ?: flowOf(initial.displayHint) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, initial.displayHint)
        else session.flatMapLatest { it?.videoSize ?: flowOf(initial.displayHint) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, initial.displayHint)
    val firstFrame =
        if (initial.isWs) _wsSession.flatMapLatest { it?.firstFrame ?: flowOf(false) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
        else session.flatMapLatest { it?.firstFrame ?: flowOf(false) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val stats =
        if (initial.isWs) _wsSession.flatMapLatest { it?.stats ?: emptyFlow() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.webrtc_phone.android.rtc.StreamStats())
        else session.flatMapLatest { it?.stats ?: emptyFlow() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.webrtc_phone.android.rtc.StreamStats())
    // 文件/终端/剪贴板：WS 模式不支持，恒为 null
    val files = if (initial.isWs) MutableStateFlow(null)
        else session.flatMapLatest { it?.files ?: flowOf(null) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val terminal = if (initial.isWs) MutableStateFlow(null)
        else session.flatMapLatest { it?.terminal ?: flowOf(null) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val inputReady = if (initial.isWs) MutableStateFlow(true)
        else session.flatMapLatest { it?.inputReady ?: flowOf(false) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 统一输入入口：WS 用 WsStreamSession.input，否则 RtcSession.input */
    private val activeInput: com.webrtc_phone.android.rtc.channel.InputProtocol?
        get() = if (isWs) _wsSession.value?.input else session.value?.input

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts = _toasts.asSharedFlow()

    var panel by mutableStateOf(Panel.NONE)
    var keyboardMode by mutableStateOf(false)
        private set
    var audioOn by mutableStateOf(true)
        private set
    var mirrored by mutableStateOf(false)
    var cameraZoom by mutableFloatStateOf(1f)
    /** 全屏模式：隐藏悬浮球/导航键/侧边把手等所有叠加控件，纯净看画面 */
    var fullscreen by mutableStateOf(false)
    /** 终端 WebView 一旦创建就保留到页面销毁（重复创建开销大且会丢失屏幕内容） */
    var terminalCreated by mutableStateOf(false)

    // ---------- 侧边栏：切换设备 ----------

    /** 复用登录会话的设备仓库（与首页同一份数据，含缩略图） */
    private val deviceRepo = c.userScope()?.devices

    /** 只有登录用户能切换（需要设备列表权限） */
    val canSwitchDevices: Boolean get() = deviceRepo != null && target.auth is StreamAuth.User

    val devices: StateFlow<List<Device>> = deviceRepo?.devices ?: MutableStateFlow(emptyList())
    val snapshots: StateFlow<Map<String, Snapshot>> = deviceRepo?.snapshots ?: MutableStateFlow(emptyMap())
    val devicesLoading: StateFlow<Boolean> = deviceRepo?.loading ?: MutableStateFlow(false)
    /** 标签（含定义与 设备→标签 映射），用于侧边栏显示设备标签 */
    val tags: StateFlow<TagsPayload> = deviceRepo?.tags ?: MutableStateFlow(TagsPayload.EMPTY)

    // ---------- 服务器中转文件模式 ----------

    /** 服务器中转文件仓库（会话级） */
    val serverFiles = c.userScope()?.serverFiles

    /**
     * 是否可用服务器中转：登录用户 + 管理员。
     * 服务端 /api/tasks 的 install/push_file 为 admin-only，非 admin 即使上传了也无法下发，故整块入口仅对 admin 开放。
     */
    val canServerRelay: Boolean
        get() = serverFiles != null && target.auth is StreamAuth.User &&
            c.sessions.session.value?.isAdmin == true

    /** 上传本地文件到服务器盘 */
    fun uploadToServer(uri: android.net.Uri, name: String, size: Long) {
        val repo = serverFiles ?: return
        viewModelScope.launch {
            runCatching { repo.upload(uri, name, size) }
                .onFailure { toast(it.message ?: "上传失败") }
                .onSuccess { toast("已上传到服务器") }
        }
    }

    /** 把服务器文件下发到当前投屏设备（apk 安装 / 其它推送到 destPath） */
    fun dispatchToDevice(file: com.webrtc_phone.android.data.model.ServerFile, destPath: String?) {
        val repo = serverFiles ?: return
        viewModelScope.launch {
            runCatching { repo.dispatch(file, target.deviceId, destPath) }
                .onSuccess { sub -> toast(if (sub.ok) "下发完成" else "下发失败：${sub.result.ifBlank { sub.status }}") }
                .onFailure { toast(it.message ?: "下发失败") }
        }
    }

    fun deleteServerFile(name: String) {
        val repo = serverFiles ?: return
        viewModelScope.launch {
            runCatching { repo.delete(name) }
                .onFailure { toast(it.message ?: "删除失败") }
                .onSuccess { toast("已删除") }
        }
    }

    fun refreshServerFiles() {
        val repo = serverFiles ?: return
        viewModelScope.launch { runCatching { repo.refresh() }.onFailure { toast(it.message ?: "刷新失败") } }
    }

    var drawerOpen by mutableStateOf(false)
        private set
    private var drawerRefreshJob: Job? = null

    /**
     * 打开设备侧边栏。投屏期间首页的实时连接已关闭（避免与视频抢带宽），
     * 这里仅在侧边栏打开时每 10 秒拉取一次设备列表与缩略图。
     */
    fun openDrawer() {
        val repo = deviceRepo
        if (repo == null || !canSwitchDevices) {
            toast("当前身份不支持切换设备")
            return
        }
        panel = Panel.NONE
        if (keyboardMode) setKeyboard(false)
        drawerOpen = true
        drawerRefreshJob?.cancel()
        drawerRefreshJob = viewModelScope.launch {
            while (true) {
                repo.refresh(showLoading = repo.devices.value.isEmpty())
                // 同时刷新标签（refresh 只拉设备/缩略图），保证侧边栏标签与首页一致
                repo.loadMeta()
                delay(DRAWER_REFRESH_MS)
            }
        }
    }

    fun closeDrawer() {
        drawerOpen = false
        drawerRefreshJob?.cancel()
        drawerRefreshJob = null
    }

    fun refreshDevices() {
        val repo = deviceRepo ?: return
        viewModelScope.launch { repo.refresh(showLoading = true) }
    }

    /**
     * 切换到另一台设备：沿用当前账号、权限与画质，回到普通投屏模式。
     * agent 协议没有“换设备”命令，等同于断开旧会话、用新目标重建。
     */
    fun switchDevice(d: Device) {
        closeDrawer()
        if (d.id == target.deviceId) return
        if (!d.online) {
            toast("${d.id} 已离线")
            return
        }
        panel = Panel.NONE
        mirrored = false
        cameraZoom = 1f
        val name = d.info.title.ifBlank { d.id }
        reconnectWith(
            target.copy(
                deviceId = d.id,
                deviceName = name,
                mode = StreamMode.Screen,
                settings = settings.stream.value,
                displayHint = d.info.primaryDisplay?.let { it.width to it.height },
            )
        )
        toast("正在切换到 $name")
    }

    // 本机剪贴板时间戳门控：只有内容变化才读正文（读正文会触发系统隐私提示）
    private var lastClipStamp = -1L

    init {
        if (initial.isWs) {
            val ws = com.webrtc_phone.android.rtc.ws.WsStreamSession(initial, c.httpFactory)
            _wsSession.value = ws
            ws.start()
        } else {
            manager.autoReconnect = settings.app.value.autoReconnect
            manager.open(initial)
            viewModelScope.launch {
                session.collectLatest { s -> s?.remoteClipboard?.collect(::applyRemoteClipboard) }
            }
        }
    }

    fun toast(msg: String) {
        _toasts.tryEmit(msg)
    }

    private val s get() = session.value

    // ---------- 按键 ----------

    fun press(key: Int) {
        if (blockedByViewOnly()) return
        activeInput?.press(key)
    }

    fun longPress(key: Int) {
        if (blockedByViewOnly()) return
        activeInput?.longPress(key)
    }

    fun rawKey(keycode: Int, down: Boolean, repeat: Int, meta: Int) {
        if (target.viewOnly) return
        activeInput?.key(keycode, if (down) 0 else 1, repeat, meta)
    }

    fun blockedByViewOnly(): Boolean {
        if (target.viewOnly) toast("当前为仅观看模式")
        return target.viewOnly
    }

    // ---------- 触控（WS/RTC 统一经 activeInput） ----------

    fun touch(pointerId: Int, action: Int, x: Int, y: Int, w: Int, h: Int) {
        activeInput?.touch(pointerId, action, x, y, w, h)
    }

    fun scroll(x: Int, y: Int, w: Int, h: Int, hScroll: Int, vScroll: Int) {
        activeInput?.scroll(x, y, w, h, hScroll, vScroll)
    }

    // ---------- 文本 ----------

    fun setKeyboard(on: Boolean) {
        if (on && blockedByViewOnly()) return
        if (keyboardMode == on) return
        keyboardMode = on
        // 注意：不再发送 hard_keyboard（远端 UHID 物理键盘）。实测在 redroid 上开启后 agent 视频流
        // 停止（0 fps，需重连恢复）；文字统一走 inject_text，远端软键盘由用户自行收起。
    }

    fun commitText(text: String) {
        activeInput?.text(text)
    }

    /**
     * 文本下发：短文本走 inject_text；多行/长文本走剪贴板 + 粘贴（WebRTC），WS 模式无剪贴板通道统一走 inject_text。
     */
    fun sendText(text: String, autoEnter: Boolean) {
        if (blockedByViewOnly() || text.isEmpty()) return
        val ok = if (isWs) {
            activeInput?.text(text) ?: false
        } else {
            val ses = s ?: return
            if (text.length > 200 || text.contains('\n'))
                ses.clipboard.push(text, paste = true, force = true) || ses.input.text(text)
            else ses.input.text(text)
        }
        if (!ok) {
            toast("控制通道未就绪")
            return
        }
        if (autoEnter) activeInput?.press(Keys.ENTER)
        toast("已发送")
    }

    fun sendQuickText(q: QuickText) = sendText(q.content, q.autoEnter)

    // ---------- 剪贴板 ----------

    fun syncLocalClipboard(context: Context, force: Boolean = false) {
        if (!settings.app.value.clipboardSync && !force) return
        if (!target.allowClipboard || target.viewOnly) return
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val desc = cm.primaryClipDescription ?: return
        val stamp = if (android.os.Build.VERSION.SDK_INT >= 26) desc.timestamp else -2L
        if (!force && stamp == lastClipStamp && stamp != -2L) return
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        lastClipStamp = stamp
        if (s?.clipboard?.push(text, force = force) == true && force) toast("已发送到云手机剪贴板")
    }

    fun pullRemoteClipboard() {
        if (s?.clipboard?.requestRemote() != true) toast("剪贴板通道未就绪")
        else pendingManualPull = true
    }

    private var pendingManualPull = false
    private var appContextRef: Context? = null

    fun appContext(): Context = appContextRef ?: c.appContext

    fun bindContext(ctx: Context) {
        appContextRef = ctx.applicationContext
    }

    private fun applyRemoteClipboard(text: String) {
        val ctx = appContextRef ?: return
        if (!settings.app.value.clipboardSync && !pendingManualPull) return
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("cloudphone", text))
        if (android.os.Build.VERSION.SDK_INT >= 26) lastClipStamp = cm.primaryClipDescription?.timestamp ?: lastClipStamp
        if (pendingManualPull) {
            pendingManualPull = false
            toast("已复制云手机剪贴板")
        }
    }

    // ---------- 音频 / 画质 / 摄像头 ----------

    val powerOff: Boolean get() = target.settings.powerOff

    fun togglePowerOff() {
        val newPowerOff = !powerOff
        settings.updateStream { it.copy(powerOff = newPowerOff) }
        val newTarget = target.copy(settings = settings.stream.value)
        toast(if (newPowerOff) "开启息屏连接 (防窥省电)" else "恢复正常亮屏连接")
        reconnectWith(newTarget)
    }

    fun toggleAudio() {
        audioOn = !audioOn
        manager.setAudioEnabled(audioOn)
    }

    fun applyQuality(transform: (StreamSettings) -> StreamSettings) {
        settings.updateStream(transform)
        reconnectWith(target.copy(settings = settings.stream.value))
    }

    fun switchCamera(params: CameraParams) {
        cameraZoom = 1f
        reconnectWith(target.copy(mode = StreamMode.Camera(params)))
    }

    val cameraParams: CameraParams? get() = (target.mode as? StreamMode.Camera)?.params

    /**
     * 切换传输方式（WebRTC <-> WS）。因渲染视图在 Activity onCreate 固定（SurfaceView vs SurfaceViewRenderer），
     * 需以新 transport 重建目标并让 StreamActivity 重建。通过 [recreateEvent] 通知 Activity。
     */
    private val _recreateEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val recreateEvent = _recreateEvent.asSharedFlow()

    fun switchTransport(to: com.webrtc_phone.android.rtc.StreamTransport) {
        if (target.transport == to) return
        if (keyboardMode) setKeyboard(false)
        // 先断开当前会话，再以新 transport 更新待启动目标
        if (isWs) { _wsSession.value?.close(); _wsSession.value = null } else manager.close()
        val t = target.copy(transport = to)
        target = t
        StreamLauncher.update(t)
        _recreateEvent.tryEmit(Unit)
    }

    private fun reconnectWith(t: StreamTarget) {
        if (keyboardMode) setKeyboard(false)
        target = t
        StreamLauncher.update(t)
        if (t.isWs) restartWs(t) else manager.restart(t)
    }

    fun reconnect() {
        if (isWs) restartWs(target) else manager.restart(target)
    }

    private fun restartWs(t: StreamTarget) {
        _wsSession.value?.close()
        val ws = com.webrtc_phone.android.rtc.ws.WsStreamSession(t, c.httpFactory)
        _wsSession.value = ws
        wsSurface?.let { ws.setSurface(it) }
        ws.start()
    }

    fun disconnect() {
        if (keyboardMode) setKeyboard(false)
        if (isWs) { _wsSession.value?.close(); _wsSession.value = null } else manager.close()
        StreamLauncher.clear()
    }

    // WS 模式渲染 Surface（由 StreamActivity 的 SurfaceView 提供）
    @Volatile private var wsSurface: android.view.Surface? = null
    fun setWsSurface(s: android.view.Surface?) {
        wsSurface = s
        _wsSession.value?.setSurface(s)
    }

    override fun onCleared() {
        super.onCleared()
        if (isWs) { _wsSession.value?.close(); _wsSession.value = null }
    }

    fun attach(sink: org.webrtc.VideoSink) = manager.attach(sink)
    fun detach(sink: org.webrtc.VideoSink) = manager.detach(sink)
    val eglContext get() = manager.eglContext

    companion object {
        private const val DRAWER_REFRESH_MS = 10_000L
    }
}
