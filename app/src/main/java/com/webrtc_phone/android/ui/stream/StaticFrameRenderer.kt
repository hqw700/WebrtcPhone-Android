package com.webrtc_phone.android.ui.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame

/** 尺寸切换时重画最近一帧，静止屏幕也无需等待设备产生新画面。 */
class StaticFrameRenderer(context: Context) : SurfaceViewRenderer(context) {
    private val main = Handler(Looper.getMainLooper())
    private val frameLock = Any()
    private var snapshot: VideoFrame? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var settling = false
    private var released = false
    private val finishSettling = Runnable {
        synchronized(frameLock) {
            settling = false
            snapshot?.release()
            snapshot = null
        }
    }
    private val redraw = Runnable {
        synchronized(frameLock) {
            if (!released) snapshot?.let { super.onFrame(it) }
        }
    }

    override fun onFrame(frame: VideoFrame) {
        synchronized(frameLock) {
            if (released) return
            if (frameWidth != frame.rotatedWidth || frameHeight != frame.rotatedHeight) {
                frameWidth = frame.rotatedWidth
                frameHeight = frame.rotatedHeight
                settling = true
                // 无布局回调时也必须释放；稳定尺寸之后不再做 CPU 快照。
                main.removeCallbacks(finishSettling)
                main.postDelayed(finishSettling, 500)
            }
            if (settling) {
                // 不能 retain 解码器的 OES 纹理：会占住 SurfaceTextureHelper 的唯一帧，
                // 阻塞后续解码。toI420 为重绘持有独立的 CPU 图像。
                val buffer = frame.buffer.toI420()
                if (buffer != null) {
                    snapshot?.release()
                    snapshot = VideoFrame(buffer, frame.rotation, frame.timestampNs)
                }
            }
            super.onFrame(frame)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) redrawAfterLayout()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        super.surfaceChanged(holder, format, width, height)
        redrawAfterLayout()
    }

    private fun redrawAfterLayout() {
        synchronized(frameLock) {
            if (released || !settling) return
            // 合并布局/Surface 回调；等尺寸提交后在下一帧重新提交完整画面。
            removeCallbacks(redraw)
            postOnAnimation(redraw)
            main.removeCallbacks(finishSettling)
            main.postDelayed(finishSettling, 150)
        }
    }

    override fun release() {
        synchronized(frameLock) {
            released = true
            settling = false
            main.removeCallbacksAndMessages(null)
            removeCallbacks(redraw)
            snapshot?.release()
            snapshot = null
        }
        super.release()
    }
}
