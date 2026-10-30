package com.webrtc_phone.android

import com.webrtc_phone.android.rtc.channel.TerminalOutputBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalOutputBufferTest {
    @Test fun burstBeyondOldMessageLimitIsDeliveredInOrder() = runTest {
        val buffer = TerminalOutputBuffer(128 * 1024)
        val events = mutableListOf<TerminalOutputBuffer.Output>()
        backgroundScope.launch { buffer.output.collect { events.add(it) } }
        runCurrent()
        val expected = (0..999).joinToString("") { "$it:中文\n" }
        (0..999).forEach { buffer.append("$it:中文\n".toByteArray()) }
        runCurrent()
        assertEquals(expected, events.flatMap { it.bytes.asIterable() }.toByteArray().toString(Charsets.UTF_8))
        assertTrue(events.none { it.skippedBytes > 0 })
    }

    @Test fun laggingReaderGetsExplicitResetAndBoundedTail() = runTest {
        val buffer = TerminalOutputBuffer(8)
        val events = mutableListOf<TerminalOutputBuffer.Output>()
        backgroundScope.launch { buffer.output.collect { events.add(it) } }
        runCurrent()
        buffer.append("abcdefgh".toByteArray())
        buffer.append("ijkl".toByteArray())
        runCurrent()
        val recovered = events.last()
        assertTrue(recovered.reset)
        assertEquals(4L, recovered.skippedBytes)
        assertEquals("efghijkl", recovered.bytes.toString(Charsets.UTF_8))
        assertEquals(8, buffer.history().size)
    }

    @Test fun reopenedReaderReplaysHistoryAndClearDoesNotReportOverflow() = runTest {
        val buffer = TerminalOutputBuffer(8)
        buffer.append("hello".toByteArray())
        val events = mutableListOf<TerminalOutputBuffer.Output>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { buffer.output.collect { events.add(it) } }
        assertEquals("hello", events.single().bytes.toString(Charsets.UTF_8))
        buffer.clear()
        buffer.append("new".toByteArray())
        assertTrue(events.all { it.skippedBytes == 0L })
        assertEquals("new", buffer.history().toString(Charsets.UTF_8))
    }
}
