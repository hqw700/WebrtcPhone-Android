package com.webrtc_phone.android.rtc.channel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/** 按字节游标读取，通知可以合并，终端字节不能像 SharedFlow 消息一样静默跳过。 */
internal class TerminalOutputBuffer(private val capacity: Int = 512 * 1024) {
    data class Output(val bytes: ByteArray, val reset: Boolean, val skippedBytes: Long = 0)
    private val chunks = ArrayDeque<ByteArray>()
    private var end = 0L
    private var start = 0L
    private var epoch = 0L
    private val changed = MutableStateFlow(0L)

    @Synchronized fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        chunks.addLast(bytes.copyOf())
        end += bytes.size
        while (end - start > capacity) {
            val first = chunks.removeFirst()
            val discard = minOf(end - start - capacity, first.size.toLong()).toInt()
            start += discard
            if (discard < first.size) chunks.addFirst(first.copyOfRange(discard, first.size))
        }
        changed.value++
    }

    @Synchronized fun history(): ByteArray = read(start, epoch).first.bytes
    @Synchronized fun clear() {
        chunks.clear()
        start = end
        epoch++
        changed.value++
    }

    private fun read(cursor: Long, previousEpoch: Long): Pair<Output, Pair<Long, Long>> {
        val reset = previousEpoch != epoch || cursor < start
        val position = if (reset) start else cursor
        var offset = start
        val output = ByteArrayOutputStream()
        for (bytes in chunks) {
            if (offset + bytes.size > position) {
                val begin = maxOf(0L, position - offset).toInt()
                output.write(bytes, begin, bytes.size - begin)
            }
            offset += bytes.size
        }
        val skipped = if (previousEpoch == epoch) maxOf(0L, start - cursor) else 0L
        return Output(output.toByteArray(), reset, skipped) to (end to epoch)
    }

    val output = flow {
        var cursor = 0L
        var previousEpoch = -1L
        changed.collect {
            val (event, next) = synchronized(this@TerminalOutputBuffer) { read(cursor, previousEpoch) }
            if (event.reset || event.bytes.isNotEmpty()) emit(event)
            cursor = next.first
            previousEpoch = next.second
        }
    }
}
