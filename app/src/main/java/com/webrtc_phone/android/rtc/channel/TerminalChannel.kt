package com.webrtc_phone.android.rtc.channel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.DataChannel

enum class TerminalState { CONNECTING, OPEN, CLOSED }

/**
 * 调试终端（adb-channel，agent 端为 PTY 反代 shell）。
 * 协议：首帧 `{"type":"init","rows":N,"cols":M}`（二进制帧发送 UTF-8 JSON），之后双向裸字节流。
 *
 * 通道随会话存活，终端 UI 关闭不影响 shell；输出保留最近 512 KiB 字节，
 * UI 重新打开时回放，体验与本地终端一致。
 */
class TerminalChannel(private val channel: SafeChannel) {

    private val _state = MutableStateFlow(
        if (channel.isOpen) TerminalState.OPEN else TerminalState.CONNECTING
    )
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val outputBuffer = TerminalOutputBuffer()
    internal val output = outputBuffer.output

    @Volatile
    var initialized = false
        private set

    init {
        channel.observe(object : SafeChannel.Listener {
            override fun onState(state: DataChannel.State) {
                _state.value = when (state) {
                    DataChannel.State.OPEN -> TerminalState.OPEN
                    DataChannel.State.CLOSING, DataChannel.State.CLOSED -> TerminalState.CLOSED
                    else -> TerminalState.CONNECTING
                }
            }

            override fun onMessage(data: ByteArray, binary: Boolean) {
                outputBuffer.append(data)
            }
        })
    }

    /** 拉取当前可回放的历史输出 */
    fun history(): ByteArray = outputBuffer.history()

    /**
     * 终端尺寸确定后调用；同一通道只启动一次 PTY。
     * agent 不支持运行时 resize（init 之后的任何字节都会写入 PTY），因此后续调用直接忽略。
     */
    fun start(rows: Int, cols: Int): Boolean {
        if (initialized) return true
        val payload = JSONObject().put("type", "init").put("rows", rows).put("cols", cols).toString()
        val ok = channel.sendBytes(payload.toByteArray(), binary = true)
        if (ok) initialized = true
        return ok
    }

    fun write(bytes: ByteArray): Boolean = initialized && channel.sendBytes(bytes, binary = true)

    fun pollState(): TerminalState {
        val s = when (channel.state()) {
            DataChannel.State.OPEN -> TerminalState.OPEN
            DataChannel.State.CONNECTING -> TerminalState.CONNECTING
            else -> TerminalState.CLOSED
        }
        _state.value = s
        return s
    }

    fun clearHistory() = outputBuffer.clear()
}
