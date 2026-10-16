package com.webrtc_phone.android.ui.stream

/**
 * 视图坐标 → 远端视频帧坐标。视频在视图内按等比居中（fit）显示，
 * 先求内容矩形，再线性换算；落在黑边上的点返回 null（按下时丢弃，移动时钳制）。
 */
class TouchMapper(
    val viewW: Int,
    val viewH: Int,
    val videoW: Int,
    val videoH: Int,
) {
    private val scale: Float
    private val offX: Float
    private val offY: Float

    init {
        val s = if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) 0f
        else minOf(viewW.toFloat() / videoW, viewH.toFloat() / videoH)
        scale = s
        offX = (viewW - videoW * s) / 2f
        offY = (viewH - videoH * s) / 2f
    }

    val valid: Boolean get() = scale > 0f

    /** @param clamp true 时越界点钳制到边缘（用于 MOVE/UP，保证远端手势完整） */
    fun map(x: Float, y: Float, clamp: Boolean): Pair<Int, Int>? {
        if (!valid) return null
        var vx = (x - offX) / scale
        var vy = (y - offY) / scale
        val inside = vx in 0f..videoW.toFloat() && vy in 0f..videoH.toFloat()
        if (!inside && !clamp) return null
        vx = vx.coerceIn(0f, (videoW - 1).toFloat())
        vy = vy.coerceIn(0f, (videoH - 1).toFloat())
        return Math.round(vx) to Math.round(vy)
    }
}
