package com.webrtc_phone.android.rtc.channel

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** 发送端抽象：真实实现是 DataChannel，单测中替换为内存实现 */
fun interface TextSender {
    /** @return false 表示通道不可用 */
    fun send(text: String): Boolean
}

/** Android KeyEvent 常量（避免在纯逻辑层依赖 android.view） */
object Keys {
    const val HOME = 3
    const val BACK = 4
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val TAB = 61
    const val ENTER = 66
    const val DEL = 67
    const val MENU = 82
    const val MEDIA_PLAY_PAUSE = 85
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val ESCAPE = 111
    const val FORWARD_DEL = 112
    const val VOLUME_MUTE = 164
    const val APP_SWITCH = 187
    const val SCREENSHOT = 120
    const val NOTIFICATION = 83
    const val WAKEUP = 224
    const val SLEEP = 223
}

object TouchAction {
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
    const val CANCEL = 3
}

/**
 * input-channel 协议编码（与 agent webrtc.go 的 input-channel OnMessage 对应）。
 * 仅观看模式下全部丢弃（服务端同样会拦截，这里是客户端第一道防线）。
 */
class InputProtocol(
    private val sender: TextSender,
    private val viewOnly: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val seq = AtomicLong(0)

    /** 坐标为视频帧像素坐标；w/h 为帧尺寸（agent 依此换算到真实屏幕） */
    fun touch(pointerId: Int, action: Int, x: Int, y: Int, w: Int, h: Int): Boolean {
        if (viewOnly() || w <= 0 || h <= 0) return false
        // agent 只识别 0/1/2，取消按抬起处理，避免远端残留按下态
        val a = if (action == TouchAction.CANCEL) TouchAction.UP else action
        return sender.send(
            JSONObject()
                .put("type", "touch")
                .put("id", pointerId)
                .put("seq", seq.incrementAndGet())
                .put("client_ts_ms", clock())
                .put("action", a)
                .put("x", x.coerceIn(0, w))
                .put("y", y.coerceIn(0, h))
                .put("w", w)
                .put("h", h)
                .toString()
        )
    }

    fun key(keycode: Int, action: Int, repeat: Int = 0, meta: Int = 0): Boolean {
        if (viewOnly()) return false
        return sender.send(
            JSONObject()
                .put("type", "inject_keycode")
                .put("action", action)
                .put("keycode", keycode)
                .put("repeat", repeat)
                .put("meta", meta)
                .toString()
        )
    }

    /** 单击按键：DOWN + UP */
    fun press(keycode: Int, meta: Int = 0): Boolean =
        key(keycode, 0, meta = meta) && key(keycode, 1, meta = meta)

    /** 长按（如电源键长按唤出关机菜单）：DOWN + 若干 repeat + UP */
    fun longPress(keycode: Int): Boolean {
        if (!key(keycode, 0)) return false
        for (i in 1..LONG_PRESS_REPEATS) key(keycode, 0, repeat = i)
        return key(keycode, 1)
    }

    /** 文本注入。非 ASCII 由 agent 自动转剪贴板粘贴落屏 */
    fun text(text: String): Boolean {
        if (viewOnly() || text.isEmpty()) return false
        return sender.send(JSONObject().put("type", "inject_text").put("text", text).toString())
    }

    fun scroll(x: Int, y: Int, w: Int, h: Int, hScroll: Int, vScroll: Int): Boolean {
        if (viewOnly() || w <= 0 || h <= 0) return false
        return sender.send(
            JSONObject()
                .put("type", "inject_scroll")
                .put("seq", seq.incrementAndGet())
                .put("client_ts_ms", clock())
                .put("x", x).put("y", y).put("w", w).put("h", h)
                .put("scroll_h", hScroll)
                .put("scroll_v", vScroll)
                .toString()
        )
    }

    /** 远端 UHID 虚拟物理键盘开关：开启后远端软键盘不再弹出 */
    fun hardKeyboard(on: Boolean): Boolean {
        if (viewOnly()) return false
        return sender.send(
            JSONObject().put("type", "hard_keyboard").put("action", if (on) "on" else "off").toString()
        )
    }

    companion object {
        private const val LONG_PRESS_REPEATS = 20
    }
}
