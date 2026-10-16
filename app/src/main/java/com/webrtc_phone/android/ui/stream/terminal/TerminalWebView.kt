package com.webrtc_phone.android.ui.stream.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.webrtc_phone.android.rtc.channel.TerminalChannel

/** xterm.js → native：键盘输入（base64，保证控制字符/UTF-8 无损） */
class TerminalBridge(private val onInput: (ByteArray) -> Unit) {
    @JavascriptInterface
    fun write(b64: String) {
        runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()?.let(onInput)
    }
}

/**
 * 终端 WebView 宿主。
 * 注意：WebView 必须懒创建——进程内一旦初始化 Chromium，部分机型 libwebrtc 会枚举不到
 * H264 硬解，之后的 SDP 协商拒绝视频。只在会话已连接且用户打开终端时才创建。
 * 安全：只加载 file:///android_asset/terminal，禁止网络、禁止跳转、禁止 content:// 访问。
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class TerminalWebView(context: Context) : WebView(context) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var channel: TerminalChannel? = null
    private var pageReady = false
    private var startedFor: TerminalChannel? = null

    init {
        setBackgroundColor(0)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            // file:///android_asset 子资源（xterm.js/css）需要文件访问；页面跳转已全部拦截，只会加载内置资源
            allowFileAccess = true
            allowContentAccess = false
            blockNetworkLoads = true
            setSupportZoom(false)
        }
        addJavascriptInterface(TerminalBridge { bytes -> channel?.write(bytes) }, "AndroidBridge")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                maybeStart()
            }
        }
        loadUrl("file:///android_asset/terminal/index.html")
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                Log.d("Terminal", "[xterm] ${m.message()} @${m.lineNumber()}")
                return true
            }
        }
    }

    fun bind(ch: TerminalChannel?) {
        if (channel === ch) {
            maybeStart()
            return
        }
        channel = ch
        if (pageReady) evaluateJavascript("term.reset()", null)
        startedFor = null
        maybeStart()
    }

    /** 页面就绪 + 通道 OPEN 后：首次 init PTY；重新绑定时回放历史 */
    fun maybeStart() {
        val ch = channel ?: return
        if (!pageReady || startedFor === ch) return
        if (ch.pollState() != com.webrtc_phone.android.rtc.channel.TerminalState.OPEN) return
        startedFor = ch
        if (ch.initialized) {
            write(ch.history())
            focusTerminal()
            return
        }
        measureAndInit(ch, attempt = 0)
    }

    /**
     * 等页面完成布局后再测量行列：agent 不支持运行时 resize，init 时的尺寸即终身尺寸。
     * 行数过小说明布局未就绪，最多重试 ~2s。
     */
    private fun measureAndInit(ch: TerminalChannel, attempt: Int) {
        main.postDelayed({
            if (channel !== ch) return@postDelayed
            evaluateJavascript("fitTerminal()") { result ->
                val parts = result?.trim('"')?.split(',').orEmpty()
                val cols = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val rows = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if ((rows < 5 || cols < 20) && attempt < 10) {
                    measureAndInit(ch, attempt + 1)
                    return@evaluateJavascript
                }
                if (!ch.start(rows.coerceAtLeast(10), cols.coerceAtLeast(40))) startedFor = null
                focusTerminal()
            }
        }, 200)
    }

    fun write(bytes: ByteArray) {
        if (bytes.isEmpty() || !pageReady) return
        // 大块输出分片，避免单次 evaluateJavascript 过大
        var off = 0
        while (off < bytes.size) {
            val end = minOf(bytes.size, off + 32 * 1024)
            val b64 = Base64.encodeToString(bytes, off, end - off, Base64.NO_WRAP)
            evaluateJavascript("termWrite('$b64')", null)
            off = end
        }
    }

    fun refit() {
        if (pageReady) evaluateJavascript("fitTerminal()", null)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val r = super.onTouchEvent(event)
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) focusTerminal()
        return r
    }

    fun focusTerminal() {
        if (pageReady) evaluateJavascript("focusTerm()", null)
        // Compose 宿主会抢回焦点，这里显式请求并弹出输入法
        isFocusableInTouchMode = true
        requestFocus()
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    fun clear() {
        if (pageReady) evaluateJavascript("term.clear()", null)
        channel?.clearHistory()
    }

    fun release() {
        channel = null
        removeJavascriptInterface("AndroidBridge")
        destroy()
    }
}
