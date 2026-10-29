package com.webrtc_phone.android

import com.webrtc_phone.android.host.HostCommands
import org.junit.Assert.assertEquals
import org.junit.Test

class HostCommandsTest {
    @Test fun pidParsingRejectsPermissionErrors() {
        assertEquals("", HostCommands.firstPid("su: permission denied"))
        assertEquals("", HostCommands.firstPid(""))
        assertEquals("123", HostCommands.firstPid("123 456\n"))
        assertEquals("789", HostCommands.firstPid("789\n123"))
    }

    @Test fun shellTreatsConfigurationAsOneLiteralArgument() {
        val values = listOf("", "a b", "a'b\"c", "\$(printf injected)", "`printf injected`", "x; printf injected", "line1\nline2", "turn:user:pa\$word@host:3478")
        for (value in values) {
            val process = ProcessBuilder("sh", "-c", "printf '%s' " + HostCommands.quote(value)).start()
            val actual = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor())
            assertEquals(value, actual)
        }
    }
}
