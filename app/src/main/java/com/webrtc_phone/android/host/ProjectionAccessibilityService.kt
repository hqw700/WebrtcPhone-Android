package com.webrtc_phone.android.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.webrtc_phone.android.rtc.channel.Keys
import com.webrtc_phone.android.rtc.channel.TouchAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class ProjectionAccessibilityService : AccessibilityService() {
    companion object {
        private val connection = MutableStateFlow(false)
        val connected = connection.asStateFlow()
        internal var active: ProjectionAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        active = this
        connection.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        clearConnection()
        return super.onUnbind(intent)
    }

    private fun clearConnection() {
        if (active === this) {
            active = null
            connection.value = false
        }
    }

    override fun onDestroy() {
        clearConnection()
        super.onDestroy()
    }
}

internal class ProjectionGestures(private val displaySize: () -> Pair<Int, Int>) {
    private var pointer: Int? = null
    private var path: Path? = null
    private var downAt = 0L
    private var points = 0
    private var size: Pair<Int, Int>? = null

    fun receive(text: String) {
        val service = ProjectionAccessibilityService.active ?: run {
            reset()
            return
        }
        val input = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (input.optString("type")) {
            "", "touch" -> if (input.has("action") && input.has("x") && input.has("y")) touch(service, input)
            "inject_keycode" -> if (input.optInt("action", -1) == 1) {
                navigation(input.optInt("keycode"))
            }
        }
    }

    fun navigation(keycode: Int) {
        val action = when (keycode) {
            Keys.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            Keys.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            Keys.APP_SWITCH -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return
        }
        ProjectionAccessibilityService.active?.performGlobalAction(action)
    }

    private fun touch(service: AccessibilityService, input: JSONObject) {
        val frameWidth = input.optInt("w")
        val frameHeight = input.optInt("h")
        val id = input.optInt("id")
        val display = displaySize()
        val (width, height) = display
        if (frameWidth <= 0 || frameHeight <= 0 || width <= 0 || height <= 0) return
        // 旋转期间旧帧仍可能抵达，避免按新屏幕坐标执行旧手势。
        val ratio = frameWidth.toDouble() * height / (frameHeight.toDouble() * width)
        if (ratio !in 0.95..1.05) {
            reset()
            return
        }
        val x = (input.optInt("x").coerceIn(0, frameWidth).toFloat() / frameWidth * width).coerceAtMost(width - 1f)
        val y = (input.optInt("y").coerceIn(0, frameHeight).toFloat() / frameHeight * height).coerceAtMost(height - 1f)
        when (input.optInt("action", -1)) {
            TouchAction.DOWN -> if (pointer == null || SystemClock.uptimeMillis() - downAt > 5_000) {
                pointer = id
                path = Path().apply { moveTo(x, y) }
                size = display
                downAt = SystemClock.uptimeMillis()
                points = 0
            }
            TouchAction.MOVE -> if (pointer == id && size == display && points < 512) {
                path?.lineTo(x, y)
                points++
            }
            TouchAction.UP -> if (pointer == id) {
                val stroke = path
                val duration = SystemClock.uptimeMillis() - downAt
                val sameDisplay = size == display
                reset()
                if (stroke == null || !sameDisplay || duration > 5_000) return
                stroke.lineTo(x, y)
                service.dispatchGesture(
                    GestureDescription.Builder().addStroke(
                        GestureDescription.StrokeDescription(stroke, 0, duration.coerceIn(30L, 1_500L))
                    ).build(), null, null
                )
            }
            TouchAction.CANCEL -> if (pointer == id) reset()
        }
    }

    fun reset() {
        pointer = null
        path = null
        size = null
    }
}
