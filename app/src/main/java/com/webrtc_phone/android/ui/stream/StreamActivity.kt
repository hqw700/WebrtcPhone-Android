package com.webrtc_phone.android.ui.stream

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.webrtc_phone.android.container
import com.webrtc_phone.android.data.settings.ThemeMode
import com.webrtc_phone.android.ui.theme.WebrtcPhoneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 投屏页。全屏沉浸，系统返回键 = 云手机返回键，退出走工具面板 / 二次确认。
 * 连接由 SessionManager 托管：页面退后台只停止渲染，回到前台秒恢复。
 */
class StreamActivity : ComponentActivity() {

    private lateinit var vm: StreamViewModel
    private var renderer: SurfaceViewRenderer? = null
    private lateinit var videoView: android.view.View
    private var lastBackAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = StreamLauncher.pending
        if (target == null) {
            finish()
            return
        }
        val c = container
        vm = ViewModelProvider(this, viewModelFactory { initializer { StreamViewModel(c, target) } })[StreamViewModel::class.java]
        vm.bindContext(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        videoView = if (target.isWs) {
            // WS 模式：H.264 由 MediaCodec 解码到 SurfaceView
            android.view.SurfaceView(this).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(h: android.view.SurfaceHolder) { vm.setWsSurface(h.surface) }
                    override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int) { vm.setWsSurface(h.surface) }
                    override fun surfaceDestroyed(h: android.view.SurfaceHolder) { vm.setWsSurface(null) }
                })
            }
        } else {
            StaticFrameRenderer(this).apply {
                init(vm.eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                // 固定 Surface 为视图尺寸，避免首帧尺寸更新再次重建缓冲区。
                // 静止画面可能没有后续帧，硬件缩放时会暂时显示被拉伸的旧缓冲。
                setEnableHardwareScaler(false)
                isFocusable = true
                isFocusableInTouchMode = true
                renderer = this
            }
        }
        setupTouch()

        setContent {
            WebrtcPhoneTheme(ThemeMode.DARK) {
                StreamScreen(vm, videoView, onScreenshot = ::screenshot, onExit = ::exit)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.toasts.collect { Toast.makeText(this@StreamActivity, it, Toast.LENGTH_SHORT).show() } }
                launch { vm.videoSize.collect { applyOrientation() } }
                launch {
                    // 传输方式切换：结束当前页并以新 transport 重开（渲染视图在 onCreate 固定，必须重建）
                    vm.recreateEvent.collect {
                        StreamLauncher.pending?.let { t ->
                            finish()
                            StreamLauncher.launch(this@StreamActivity, t)
                        }
                    }
                }
                launch { vm.settings.app.collect { applyOrientation(); applyKeepScreenOn(it.keepScreenOn) } }
                launch { snapshotFlow { vm.mirrored }.collect { renderer?.setMirror(it) } }
                launch {
                    snapshotFlow { vm.keyboardMode }.collect { on ->
                        if (!on) {
                            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                            hideSystemBars()
                        }
                    }
                }
            }
        }
    }

    private fun setupTouch() {
        val forwarder = TouchForwarder(vm) {
            if (vm.settings.app.value.haptics) videoView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        // 摄像头模式：双指捏合 = 数字变焦（1x–8x）
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                vm.cameraZoom = (vm.cameraZoom * d.scaleFactor).coerceIn(1f, 8f)
                return true
            }
        })
        videoView.setOnTouchListener { v, e ->
            if (vm.target.isCamera) {
                scale.onTouchEvent(e)
                true
            } else forwarder.onTouch(v, e)
        }
        videoView.setOnGenericMotionListener(forwarder)
    }

    private fun applyOrientation() {
        val mode = vm.settings.app.value.orientationLock
        val size = vm.videoSize.value
        val orientation = when (mode) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "sensor" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            // 跟随画面：云手机横屏（游戏/视频）时本机自动转横屏
            else -> when {
                size == null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                size.first > size.second -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
        if (requestedOrientation != orientation) requestedOrientation = orientation
    }

    private fun applyKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun onBack() {
        when {
            vm.drawerOpen -> vm.closeDrawer()
            vm.panel != Panel.NONE -> vm.panel = Panel.NONE
            vm.keyboardMode -> vm.setKeyboard(false)
            vm.target.viewOnly || vm.target.isCamera || vm.phase.value !is com.webrtc_phone.android.rtc.ConnectionPhase.Connected -> {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000) exit() else {
                    lastBackAt = now
                    Toast.makeText(this, "再按一次退出投屏", Toast.LENGTH_SHORT).show()
                }
            }
            else -> vm.press(com.webrtc_phone.android.rtc.channel.Keys.BACK)
        }
    }

    /** 物理键盘直通（外接键盘/蓝牙键盘），媒体音量键留给本机 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val passthrough = !vm.keyboardMode && !vm.drawerOpen && vm.panel == Panel.NONE && !vm.target.viewOnly &&
            event.device?.isVirtual == false && code != KeyEvent.KEYCODE_BACK &&
            code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.isFromSource(android.view.InputDevice.SOURCE_KEYBOARD)
        if (passthrough && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
            vm.rawKey(code, event.action == KeyEvent.ACTION_DOWN, event.repeatCount, event.metaState)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        renderer?.let { vm.attach(it) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            // Android 10+ 只有前台获得焦点时才能读剪贴板
            vm.syncLocalClipboard(this)
        }
    }

    override fun onStop() {
        renderer?.let { vm.detach(it) }
        super.onStop()
    }

    override fun onDestroy() {
        renderer?.let {
            vm.detach(it)
            it.release()
        }
        super.onDestroy()
    }

    private fun exit() {
        vm.disconnect()
        finish()
    }

    // ---------- 截图 ----------

    private fun screenshot() {
        val r = renderer ?: run {
            Toast.makeText(this, "WS 模式暂不支持截图", Toast.LENGTH_SHORT).show()
            return
        }
        r.addFrameListener({ bmp ->
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { saveImage(copy) }.getOrNull() }
                Toast.makeText(this@StreamActivity, if (ok != null) "截图已保存到相册" else "截图保存失败", Toast.LENGTH_SHORT).show()
            }
        }, 1f)
    }

    private fun saveImage(bmp: Bitmap): String {
        val name = "CloudPhone_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CloudPhone")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
            contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            return uri.toString()
        }
        // 旧系统：保存到应用专属图片目录（无需存储权限）
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "CloudPhone").apply { mkdirs() }
        val f = File(dir, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return f.absolutePath
    }
}
