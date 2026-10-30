package com.webrtc_phone.android

import com.webrtc_phone.android.host.ProcessDeadline
import com.webrtc_phone.android.host.TimedShell
import org.junit.Assert.*
import org.junit.Test
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
}
