package com.webrtc_phone.android.ui.stream.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.webrtc_phone.android.rtc.channel.TerminalChannel
import com.webrtc_phone.android.rtc.channel.TerminalOutputBuffer

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
    private val pendingOutput = ByteArrayOutputStream()
    private var flushScheduled = false
    private val flushOutput = Runnable {
        flushScheduled = false
        if (pageReady && pendingOutput.size() > 0) {
            val bytes = pendingOutput.toByteArray()
            pendingOutput.reset()
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(32 * 1024, bytes.size - offset)
                val b64 = Base64.encodeToString(bytes, offset, count, Base64.NO_WRAP)
                evaluateJavascript("termWrite('$b64')", null)
                offset += count
            }
        }
    }
    private val preferences = context.getSharedPreferences("terminal_display", Context.MODE_PRIVATE)
    private var fontSize = preferences.getFloat("font_size", 13f).coerceIn(9f, 26f)
    private var multiTouch = false
    private var selecting = false
    private var gestureId = 0
    private var selectionMode: ActionMode? = null
    private val applyFont = Runnable {
        if (pageReady) evaluateJavascript("setTerminalFontSize($fontSize)", null)
    }
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            selectionMode?.finish()
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            fontSize = (fontSize * detector.scaleFactor).coerceIn(9f, 26f)
            removeCallbacks(applyFont)
            postOnAnimation(applyFont)
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            preferences.edit().putFloat("font_size", fontSize).apply()
        }
    })
    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (selectionMode != null) selectionMode?.finish() else focusTerminal()
            return true
        }
        override fun onLongPress(event: MotionEvent) {
            if (!multiTouch && pageReady && width > 0 && height > 0) {
                selecting = true
                val currentGesture = gestureId
                cancelWebTouch(event)
                selectAt(event, false) { selected ->
                    if (pageReady && currentGesture == gestureId && !multiTouch && selected == "true") showSelectionMenu()
                }
            }
        }
    })

    private fun cancelWebTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.onTouchEvent(cancel)
        cancel.recycle()
    }

    private fun selectAt(event: MotionEvent, extend: Boolean, callback: ((String?) -> Unit)? = null) {
        val x = event.x / width.coerceAtLeast(1)
        val y = event.y / height.coerceAtLeast(1)
        evaluateJavascript("terminalSelectAt($x,$y,$extend)", callback)
    }

    private fun showSelectionMenu() {
        if (selectionMode != null) return
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        selectionMode = startActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, 1, 0, "复制").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, 2, 1, "全选")
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    1 -> evaluateJavascript("term.getSelection()") { result ->
                        val text = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                        if (!text.isNullOrEmpty()) {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("终端", text))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                        mode.finish()
                    }
                    2 -> evaluateJavascript("term.selectAll()", null)
                    else -> return false
                }
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {
                selecting = false
                selectionMode = null
                if (pageReady) evaluateJavascript("term.clearSelection()", null)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    init {
        setBackgroundColor(0)
        setOnLongClickListener { true }
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
                applyFont.run()
                flushOutput.run()
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
        selectionMode?.finish()
        channel = ch
        main.removeCallbacks(flushOutput)
        pendingOutput.reset()
        flushScheduled = false
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

    internal fun writeOutput(output: TerminalOutputBuffer.Output) {
        if (output.reset) {
            main.removeCallbacks(flushOutput)
            pendingOutput.reset()
            flushScheduled = false
            if (pageReady) evaluateJavascript("term.reset()", null)
        }
        if (output.skippedBytes > 0) {
            Toast.makeText(context, "输出超过历史缓存，已重新加载最近内容", Toast.LENGTH_LONG).show()
        }
        write(output.bytes)
    }

    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // 将一帧内的碎片合并，避免每个 DataChannel 消息都跨进程调用 JS。
        pendingOutput.write(bytes)
        if (!flushScheduled) {
            flushScheduled = true
            if (pageReady) main.postDelayed(flushOutput, 16)
        }
    }

    fun refit() {
        if (pageReady) evaluateJavascript("fitTerminal()", null)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureId++
                multiTouch = false
                selecting = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gestureId++
                multiTouch = true
                selecting = false
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.onTouchEvent(cancel)
                tapDetector.onTouchEvent(cancel)
                cancel.recycle()
            }
        }
        scaleDetector.onTouchEvent(event)
        if (!multiTouch) {
            tapDetector.onTouchEvent(event)
            if (selecting && event.actionMasked == MotionEvent.ACTION_MOVE) selectAt(event, true)
        }
        val consumed = if (multiTouch || selecting || selectionMode != null) true else super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            selecting = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return consumed
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
        main.removeCallbacks(flushOutput)
        pendingOutput.reset()
        flushScheduled = false
        if (pageReady) evaluateJavascript("term.clear()", null)
        channel?.clearHistory()
    }

    fun release() {
        gestureId++
        selectionMode?.finish()
        removeCallbacks(applyFont)
        main.removeCallbacksAndMessages(null)
        pendingOutput.reset()
        pageReady = false
        channel = null
        removeJavascriptInterface("AndroidBridge")
        destroy()
    }
}
