package com.webrtc_phone.android.rtc.channel

import android.util.Log
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * DataChannel 安全封装。
 *
 * libwebrtc 的 DataChannel 在 native 对象释放后再调用任何方法都会 SIGSEGV（try/catch 拦不住），
 * 所以所有跨线程发送都经过 [lock] + [closed] 判定；[release] 在同一把锁内注销观察者并释放引用。
 * 观察者回调运行在 WebRTC signaling 线程：这里只拷贝数据并转投 [dispatcher]，
 * signaling 线程永不等待本锁——send/close/unregister 都会同步切到 signaling 线程，
 * 回调里若取锁或同步发送就会死锁。
 */
class SafeChannel(
    val raw: DataChannel,
    /** 回调派发线程（单线程保证顺序）。回调绝不在 signaling 线程内执行业务逻辑 */
    private val dispatcher: Executor,
) : TextSender {

    val label: String = raw.label()

    private val lock = Any()

    @Volatile
    var closed = false
        private set

    interface Listener {
        fun onState(state: DataChannel.State) {}
        fun onMessage(data: ByteArray, binary: Boolean) {}
        fun onBufferedAmountChange(previous: Long) {}
    }

    fun observe(listener: Listener) {
        if (closed) return
        raw.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {
                if (closed) return
                post { listener.onBufferedAmountChange(previous) }
            }

            override fun onStateChange() {
                if (closed) return
                val st = raw.state()
                post { listener.onState(st) }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (closed) return
                val bb = buffer.data
                val bytes = ByteArray(bb.remaining())
                bb.get(bytes)
                val binary = buffer.binary
                post { listener.onMessage(bytes, binary) }
            }
        })
    }

    private fun post(block: () -> Unit) {
        try {
            dispatcher.execute { if (!closed) block() }
        } catch (_: RejectedExecutionException) {
        }
    }

    val isOpen: Boolean
        get() = synchronized(lock) { !closed && raw.state() == DataChannel.State.OPEN }

    fun state(): DataChannel.State? = synchronized(lock) { if (closed) null else raw.state() }

    fun bufferedAmount(): Long = synchronized(lock) { if (closed) 0 else raw.bufferedAmount() }

    override fun send(text: String): Boolean = sendBytes(text.toByteArray(Charsets.UTF_8), binary = false)

    fun sendBytes(bytes: ByteArray, binary: Boolean = true, offset: Int = 0, length: Int = bytes.size): Boolean =
        synchronized(lock) {
            if (closed || raw.state() != DataChannel.State.OPEN) return false
            raw.send(DataChannel.Buffer(ByteBuffer.wrap(bytes, offset, length), binary))
        }

    /** 主动关闭通道（通知对端）并释放 */
    fun closeAndRelease() = release(closeRemote = true)

    /**
     * 先在锁内置位：之后的 send/state 一律短路（正在执行的 send 已在锁内完成）。
     * close/unregister/dispose 放到锁外：这些调用会同步切到 signaling 线程，
     * 若此时回调正等待本锁会形成死锁。
     */
    fun release(closeRemote: Boolean = false) {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        if (closeRemote) runCatching { raw.close() }
        try {
            raw.unregisterObserver()
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterObserver($label) failed: ${t.message}")
        }
        try {
            raw.dispose()
        } catch (t: Throwable) {
            Log.w(TAG, "dispose($label) failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "SafeChannel"
    }
}
