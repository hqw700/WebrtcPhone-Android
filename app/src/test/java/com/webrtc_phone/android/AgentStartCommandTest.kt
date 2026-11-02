package com.webrtc_phone.android

import com.webrtc_phone.android.host.AgentController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AgentStartCommandTest {
    @Test fun rootModeExplicitlyEnablesLegacyAgentPrivilegeDrop() {
        val command = AgentController.buildAgentStartCommand("wss://example.com", "device", "", useShizuku = false)
        assertTrue(command.contains("${AgentController.AGENT_BIN_PATH} -root -signaling "))
        assertShellSyntax(command)
    }

    @Test fun shizukuModeKeepsCurrentShellIdentity() {
        val command = AgentController.buildAgentStartCommand("wss://example.com", "device", "", useShizuku = true)
        assertFalse(command.contains(" -root "))
        assertShellSyntax(command)
    }

    @Test fun rootCompatibilityFlagDoesNotChangeLiteralConfigurationArguments() {
        val command = AgentController.buildAgentStartCommand(
            "wss://example.com/a'b", "device with spaces", "turn:user:pa\$word@host:3478", useShizuku = false
        )
        assertTrue(command.contains("-id 'device with spaces'"))
        assertTrue(command.contains("-ice-servers 'turn:user:pa\$word@host:3478'"))
        assertShellSyntax(command)
    }

    @Test fun shizukuUsesSeparateLogFromRootMode() {
        val rootLog = AgentController.agentLogPath(false)
        val shizukuLog = AgentController.agentLogPath(true)
        assertFalse(rootLog == shizukuLog)
        val command = AgentController.buildAgentStartCommand("", "", "", useShizuku = true)
        assertTrue(command.contains("(: >> $shizukuLog) && "))
        assertTrue(command.contains(">> $shizukuLog 2>&1 &)"))
        assertFalse(command.contains(rootLog))
    }

    @Test fun logRedirectionFailureIsReturnedBeforeBackgroundLaunch() {
        // 目录无法作为日志打开，即使测试进程有 Root 权限也能稳定复现重定向失败。
        val directory = Files.createTempDirectory("agent-log-test-").toFile()
        try {
            for (useShizuku in listOf(false, true)) {
                val command = AgentController.buildAgentStartCommand("", "", "", useShizuku)
                    .replace(AgentController.agentLogPath(useShizuku), directory.absolutePath)
                val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
                val errors = process.inputStream.bufferedReader().use { it.readText() }
                assertTrue(errors, process.waitFor() != 0)
                assertTrue(errors, errors.contains(directory.absolutePath))
            }
        } finally { directory.delete() }
    }

    private fun assertShellSyntax(command: String) {
        val process = ProcessBuilder("sh", "-n", "-c", command).start()
        val errors = process.errorStream.bufferedReader().use { it.readText() }
        assertEquals(errors, 0, process.waitFor())
    }
}
