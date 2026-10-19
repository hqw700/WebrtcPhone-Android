package com.webrtc_phone.android.ui.stream

import android.annotation.SuppressLint
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.webrtc_phone.android.rtc.channel.Keys
import com.webrtc_phone.android.rtc.channel.TouchAction

/**
 * 画面触控 → 远端多点触控。
 * - 多指：按 pointerId 透传（手势缩放、多指游戏操作）；
 * - 鼠标/触控板：左键=触摸，右键=返回，滚轮=滚动；
 * - 按下点落在黑边上则整组手势丢弃，避免误触边缘。
 */
class TouchForwarder(
    private val vm: StreamViewModel,
    private val haptic: () -> Unit,
) : View.OnTouchListener, View.OnGenericMotionListener {

    private val activePointers = HashSet<Int>()

    private fun mapper(v: View): TouchMapper? {
        val size = targetSize() ?: return null
        return TouchMapper(v.width, v.height, size.first, size.second).takeIf { it.valid }
    }

    /**
     * 触控坐标系 = 设备逻辑分辨率（device_info.displays[0]），而非编码后的视频帧尺寸
     * （max_size 会缩小帧）；画面方向与设备默认方向不一致时宽高互换。与 Web 端一致，
     * agent 按此尺寸校验并换算，尺寸不符的事件会被丢弃。
     */
    private fun targetSize(): Pair<Int, Int>? {
        val video = vm.videoSize.value ?: return null
        val display = vm.session.value?.deviceInfo?.value?.primaryDisplay?.let { it.width to it.height }
            ?: vm.target.displayHint
            ?: return video
        val rotated = (video.first > video.second) != (display.first > display.second)
        return if (rotated) display.second to display.first else display
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        if (vm.target.viewOnly || vm.target.isCamera) return false
        val m = mapper(v) ?: return true
        val (w, h) = targetSize() ?: return true

        // 鼠标右键 = 返回（与 PC 端 scrcpy 一致）
        if (e.isFromSource(InputDevice.SOURCE_MOUSE) && e.actionMasked == MotionEvent.ACTION_DOWN &&
            (e.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
        ) {
            vm.press(Keys.BACK)
            return true
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val p = m.map(e.getX(i), e.getY(i), clamp = false) ?: return true
                activePointers += id
                if (e.actionMasked == MotionEvent.ACTION_DOWN) haptic()
                vm.touch(id, TouchAction.DOWN, p.first, p.second, w, h)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (id !in activePointers) continue
                    val p = m.map(e.getX(i), e.getY(i), clamp = true) ?: continue
                    vm.touch(id, TouchAction.MOVE, p.first, p.second, w, h)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                if (activePointers.remove(id)) {
                    val p = m.map(e.getX(i), e.getY(i), clamp = true) ?: return true
                    vm.touch(id, TouchAction.UP, p.first, p.second, w, h)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (!activePointers.remove(id)) continue
                    val p = m.map(e.getX(i), e.getY(i), clamp = true) ?: continue
                    vm.touch(id, TouchAction.CANCEL, p.first, p.second, w, h)
                }
                activePointers.clear()
            }
        }
        return true
    }

    override fun onGenericMotion(v: View, e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_SCROLL || vm.target.viewOnly || vm.target.isCamera) return false
        val m = mapper(v) ?: return false
        val (w, h) = targetSize() ?: return false
        val p = m.map(e.x, e.y, clamp = true) ?: return false
        val vs = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val hs = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
        vm.scroll(p.first, p.second, w, h, Math.round(hs), Math.round(vs))
        return true
    }
}
