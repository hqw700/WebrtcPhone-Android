package com.webrtc_phone.android

import com.webrtc_phone.android.host.ProcessDeadline
import com.webrtc_phone.android.host.TimedShell
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeoutException

class ShellTimeoutTest {
    @Test fun persistentShellHandlesPartialLinesAndRecoversAfterTimeout() {
        val shell = TimedShell { ProcessBuilder("sh").redirectErrorStream(true).start() }
        try {
            assertEquals("中文", shell.execute("printf '中文'", 2000).output)
            val started = System.nanoTime()
            try {
                shell.execute("sleep 2", 150)
                fail("A stalled command must time out")
            } catch (_: TimeoutException) { }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1500)
            assertFalse(shell.isAlive())
            assertEquals("recovered", shell.execute("printf recovered", 2000).output)
        } finally { shell.close() }
    }

    @Test fun oneShotDrainsBothPipesWithoutWaitingForNewline() {
        val process = ProcessBuilder("sh", "-c", "i=0; while [ \$i -lt 4000 ]; do printf abcdefghijklmnop; printf stderr >&2; i=\$((i+1)); done").start()
        val result = ProcessDeadline.run(process, 5000)
        assertEquals(0, result.exitCode)
        assertEquals(4000 * 22, result.output.length)
    }

    @Test fun oneShotCommandHasDeadline() {
        try {
            ProcessDeadline.run(ProcessBuilder("sh", "-c", "sleep 2").start(), 150)
            fail("A stalled process must time out")
        } catch (_: TimeoutException) { }
    }

    @Test fun remoteLivenessAvoidsBinderExitValueExceptionAndReusesStreams() {
        val process = RemoteStyleProcess()
        try {
            ProcessDeadline.alive(process)
            fail("exitValue polling must reproduce the Binder exception")
        } catch (error: IllegalArgumentException) {
            assertEquals("process hasn't exited", error.message)
        }
        val result = ProcessDeadline.run(process, 2000, process::remoteAlive)
        assertEquals(7, result.exitCode)
        assertEquals("stdoutstderr", result.output)
        assertEquals(1, process.stdoutRequests)
        assertEquals(1, process.stderrRequests)
        assertTrue(process.destroyed)
    }

    @Test fun remoteLivenessStillHasDeadline() {
        val process = RemoteStyleProcess(pollsBeforeExit = Int.MAX_VALUE)
        try {
            ProcessDeadline.run(process, 150, process::remoteAlive)
            fail("A stalled remote process must time out")
        } catch (_: TimeoutException) { }
        assertTrue(process.destroyed)
    }

    @Test fun remoteLivenessErrorsAreNotTreatedAsRunning() {
        val process = RemoteStyleProcess()
        val binderError = IllegalStateException("Binder unavailable")
        try {
            ProcessDeadline.run(process, 2000) { throw binderError }
            fail("A remote status error must propagate")
        } catch (error: IllegalStateException) {
            assertSame(binderError, error)
        }
        assertTrue(process.destroyed)
    }

    // IllegalThreadStateException 继承 IllegalArgumentException，Binder 只保留其父类型。
    private class RemoteStyleProcess(private val pollsBeforeExit: Int = 3) : Process() {
        private var polls = 0
        var stdoutRequests = 0
        var stderrRequests = 0
        @Volatile var destroyed = false
        private val stdout = ByteArrayInputStream("stdout".toByteArray())
        private val stderr = ByteArrayInputStream("stderr".toByteArray())

        fun remoteAlive(): Boolean = ++polls <= pollsBeforeExit
        override fun getInputStream() = stdout.also { stdoutRequests++ }
        override fun getErrorStream() = stderr.also { stderrRequests++ }
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int = throw AssertionError("Must not block on waitFor")
        override fun exitValue(): Int {
            if (polls <= pollsBeforeExit) throw IllegalArgumentException("process hasn't exited")
            return 7
        }
        override fun destroy() { destroyed = true }
    }
}
