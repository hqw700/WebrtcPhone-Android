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
import com.webrtc_phone.android.data.model.QuickText
import com.webrtc_phone.android.data.settings.StreamSettings
import com.webrtc_phone.android.rtc.CameraParams
import com.webrtc_phone.android.rtc.ConnectionPhase
import com.webrtc_phone.android.rtc.RtcSession
import com.webrtc_phone.android.rtc.StreamMode
import com.webrtc_phone.android.rtc.StreamTarget
import com.webrtc_phone.android.rtc.channel.Keys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    val phase: StateFlow<ConnectionPhase> = session.flatMapLatest { it?.phase ?: flowOf(ConnectionPhase.Closed) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionPhase.Signaling)
    val videoSize = session.flatMapLatest { it?.videoSize ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initial.displayHint)
    val firstFrame = session.flatMapLatest { it?.firstFrame ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val stats = session.flatMapLatest { it?.stats ?: emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.webrtc_phone.android.rtc.StreamStats())
    val files = session.flatMapLatest { it?.files ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val terminal = session.flatMapLatest { it?.terminal ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val inputReady = session.flatMapLatest { it?.inputReady ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts = _toasts.asSharedFlow()

    var panel by mutableStateOf(Panel.NONE)
    var keyboardMode by mutableStateOf(false)
        private set
    var audioOn by mutableStateOf(true)
        private set
    var mirrored by mutableStateOf(false)
    var cameraZoom by mutableFloatStateOf(1f)
    /** 终端 WebView 一旦创建就保留到页面销毁（重复创建开销大且会丢失屏幕内容） */
    var terminalCreated by mutableStateOf(false)

    // 本机剪贴板时间戳门控：只有内容变化才读正文（读正文会触发系统隐私提示）
    private var lastClipStamp = -1L

    init {
        manager.autoReconnect = settings.app.value.autoReconnect
        manager.open(initial)
        viewModelScope.launch {
            session.collectLatest { s -> s?.remoteClipboard?.collect(::applyRemoteClipboard) }
        }
    }

    fun toast(msg: String) {
        _toasts.tryEmit(msg)
    }

    private val s get() = session.value

    // ---------- 按键 ----------

    fun press(key: Int) {
        if (blockedByViewOnly()) return
        s?.input?.press(key)
    }

    fun longPress(key: Int) {
        if (blockedByViewOnly()) return
        s?.input?.longPress(key)
    }

    fun rawKey(keycode: Int, down: Boolean, repeat: Int, meta: Int) {
        if (target.viewOnly) return
        s?.input?.key(keycode, if (down) 0 else 1, repeat, meta)
    }

    fun blockedByViewOnly(): Boolean {
        if (target.viewOnly) toast("当前为仅观看模式")
        return target.viewOnly
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
        s?.input?.text(text)
    }

    /**
     * 文本下发：短文本走 inject_text；多行/长文本走剪贴板 + 粘贴，速度更快且不丢字。
     */
    fun sendText(text: String, autoEnter: Boolean) {
        if (blockedByViewOnly() || text.isEmpty()) return
        val ses = s ?: return
        val ok = if (text.length > 200 || text.contains('\n')) {
            ses.clipboard.push(text, paste = true, force = true) || ses.input.text(text)
        } else ses.input.text(text)
        if (!ok) {
            toast("控制通道未就绪")
            return
        }
        if (autoEnter) ses.input.press(Keys.ENTER)
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

    private fun reconnectWith(t: StreamTarget) {
        if (keyboardMode) setKeyboard(false)
        target = t
        StreamLauncher.update(t)
        manager.restart(t)
    }

    fun reconnect() {
        manager.restart(target)
    }

    fun disconnect() {
        if (keyboardMode) setKeyboard(false)
        manager.close()
        StreamLauncher.clear()
    }

    fun attach(sink: org.webrtc.VideoSink) = manager.attach(sink)
    fun detach(sink: org.webrtc.VideoSink) = manager.detach(sink)
    val eglContext get() = manager.eglContext
}
