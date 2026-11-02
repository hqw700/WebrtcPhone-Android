package com.webrtc_phone.android.host

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AgentController {

    private const val TAG = "AgentController"

    enum class RootStatus {
        GRANTED,      // 已获得 Root 权限 (uid=0)
        DENIED,       // 设备存在 su，但未授权或被拒绝
        NOT_AVAILABLE // 系统未找到 su（设备未 Root）
    }

    enum class ShizukuStatus {
        RUNNING_AND_GRANTED,
        RUNNING_WAITING_PERMISSION,
        NOT_RUNNING
    }

    const val AGENT_BIN_PATH = "/data/local/tmp/cloudphone-agent"
    const val LIBSYS_PATH = "/data/local/tmp/libsys_core.so"
    const val AGENT_LOG_PATH = "/data/local/tmp/cloudphone-agent-run.log"
    private const val SHIZUKU_AGENT_LOG_PATH = "/data/local/tmp/cloudphone-agent-shizuku-run.log"

    internal fun agentLogPath(useShizuku: Boolean): String =
        if (useShizuku) SHIZUKU_AGENT_LOG_PATH else AGENT_LOG_PATH

    data class CommandResult(val exitCode: Int, val output: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * 常驻交互式 Root Shell 会话：
     * 维持单一持久化的 su 进程，避免每次执行命令 fork 新进程引起授权通知频繁弹出。
     */
    object RootShell {
        private val shell = TimedShell { ProcessBuilder("su").redirectErrorStream(true).start() }
        fun isAlive() = shell.isAlive()
        fun close() = shell.close()
        fun execute(cmd: String): CommandResult = try {
            shell.execute(cmd).let { CommandResult(it.exitCode, it.output) }
        } catch (error: Exception) {
            CommandResult(-1, error.message ?: "Shell 命令失败")
        }
    }

    fun isSuBinaryPresent(): Boolean {
        val paths = arrayOf(
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        for (path in paths) {
            if (File(path).exists()) return true
        }
        var proc: Process? = null
        return try {
            proc = Runtime.getRuntime().exec(arrayOf("which", "su"))
            ProcessDeadline.run(proc).exitCode == 0
        } catch (_: Exception) {
            false
        } finally {
            proc?.destroy()
        }
    }

    fun checkRootStatus(): RootStatus {
        val result = RootShell.execute("id")
        return if (result.isSuccess && result.output.contains("uid=0")) {
            RootStatus.GRANTED
        } else if (isSuBinaryPresent()) {
            RootStatus.DENIED
        } else {
            RootStatus.NOT_AVAILABLE
        }
    }

    fun checkShizukuStatus(): ShizukuStatus {
        return try {
            if (!Shizuku.pingBinder()) {
                ShizukuStatus.NOT_RUNNING
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.RUNNING_AND_GRANTED
            } else {
                ShizukuStatus.RUNNING_WAITING_PERMISSION
            }
        } catch (_: Throwable) {
            ShizukuStatus.NOT_RUNNING
        }
    }

    /**
     * 检查当前 APK 是否内嵌了受控端二进制
     */
    fun hasAssets(context: Context): Boolean {
        return try {
            val assetName = getAgentAssetName()
            context.assets.open(assetName).close()
            context.assets.open("libsys_core.so").close()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun getAgentAssetName(): String {
        val abis = Build.SUPPORTED_ABIS
        if (abis != null && abis.isNotEmpty()) {
            val primary = abis[0].lowercase()
            if (primary.contains("x86_64") || primary.contains("amd64")) {
                return "cloudphone-agent-amd64"
            } else if (primary.contains("arm64") || primary.contains("aarch64")) {
                return "cloudphone-agent-arm64"
            } else if (primary.contains("arm") || primary.contains("v7a")) {
                return "cloudphone-agent-armeabi-v7a"
            }
        }
        return "cloudphone-agent-arm64"
    }

    private fun createShizukuProcess(cmd: String): ShizukuRemoteProcess {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw IOException("Shizuku 服务未运行或未被授权")
        }
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as ShizukuRemoteProcess
        } catch (e: Exception) {
            throw IOException("Shizuku 进程启动失败: ${e.message}", e)
        }
    }

    private fun runShizukuCommand(cmd: String): ShellResult {
        val process = createShizukuProcess(cmd)
        // exitValue() 的“尚未退出”异常经过 Binder 后不再是 IllegalThreadStateException。
        // 使用远端状态接口，避免正常运行中的命令被误判为失败，同时保留截止时间保护。
        return ProcessDeadline.run(process, isAlive = process::alive)
    }

    fun exec(cmd: String, useShizuku: Boolean) {
        if (useShizuku) {
            val result = runShizukuCommand(cmd)
            if (result.exitCode != 0) {
                throw IOException("Shizuku 命令执行失败 [${result.exitCode}]: ${result.output.trim()}")
            }
        } else {
            val res = RootShell.execute(cmd)
            if (!res.isSuccess) {
                throw IOException("命令执行失败 [${res.exitCode}]: ${res.output}")
            }
        }
    }

    fun execAndRead(cmd: String, useShizuku: Boolean): String {
        return if (useShizuku) {
            runShizukuCommand(cmd).output
        } else {
            val result = RootShell.execute(cmd)
            if (result.exitCode == -1) throw IOException(result.output)
            result.output
        }
    }

    fun deployBinaries(context: Context, useShizuku: Boolean) {
        val deployDir = context.externalCacheDir ?: context.cacheDir
        val agentOut = File(deployDir, "cloudphone-agent")
        val libsysOut = File(deployDir, "libsys_core.so")

        val assetAgent = getAgentAssetName()
        if (!extractAsset(context, assetAgent, agentOut) || !extractAsset(context, "libsys_core.so", libsysOut)) {
            throw IOException("提取受控端组件 $assetAgent 或 libsys_core.so 失败，请检查 assets 资源")
        }

        makeWorldReadable(deployDir)

        exec("cp ${agentOut.absolutePath} $AGENT_BIN_PATH", useShizuku)
        exec("chmod 755 $AGENT_BIN_PATH", useShizuku)
        exec("cp ${libsysOut.absolutePath} $LIBSYS_PATH", useShizuku)
        exec("chmod 644 $LIBSYS_PATH", useShizuku)
    }

    fun buildAgentStartCommand(signaling: String, deviceId: String, iceServers: String, useShizuku: Boolean = false): String {
        val sb = StringBuilder()
        val logPath = agentLogPath(useShizuku)
        // CGroup 脱壳保护
        sb.append("for cg in /sys/fs/cgroup /acct /dev/cpuset /dev/cpuctl; do ")
            .append("[ -f \"\$cg/cgroup.procs\" ] && echo \$\$ > \"\$cg/cgroup.procs\" 2>/dev/null || true; ")
            .append("done; ")

        sb.append("export CP_AGENT_JAR=$LIBSYS_PATH; ")

        val sig = signaling.trim()
        if (sig.isNotEmpty()) {
            sb.append("export CP_AGENT_SIGNALING=${HostCommands.quote(sig)}; ")
        }

        // 后台子进程的重定向失败不会反映在父 Shell 退出码中，先同步检查日志可写。
        // 在子 Shell 中检查，避免重定向错误终止常驻 Root Shell。
        sb.append("(: >> $logPath) && ")
        // double-fork + setsid + nohup 切断管道绑定
        sb.append("(setsid nohup env GODEBUG=asyncpreemptoff=1 $AGENT_BIN_PATH ")
        // v0.4.1 等旧 Agent 只有显式传 -root 才会把 CoreService 降权到 shell；新版仍兼容此参数。
        // Root Shell 启动 Go Agent 不代表 Java 核心也应以 UID 0 运行。
        if (!useShizuku) {
            sb.append("-root ")
        }
        if (sig.isNotEmpty()) {
            sb.append("-signaling ${HostCommands.quote(sig)} ")
        }
        val id = deviceId.trim()
        if (id.isNotEmpty()) {
            sb.append("-id ${HostCommands.quote(id)} ")
        }
        val ice = iceServers.trim()
        if (ice.isNotEmpty()) {
            sb.append("-ice-servers ${HostCommands.quote(ice)} ")
        }
        sb.append("< /dev/null >> $logPath 2>&1 &)")
        return sb.toString()
    }

    fun startAgent(context: Context, useShizuku: Boolean, signaling: String, deviceId: String, iceServers: String) {
        deployBinaries(context, useShizuku)
        val cmd = buildAgentStartCommand(signaling, deviceId, iceServers, useShizuku)
        Log.i(TAG, "Starting host agent")
        exec(cmd, useShizuku)
    }

    fun isAgentRunning(useShizuku: Boolean): String {
        var pid = readPid("pidof cloudphone-agent", useShizuku)
        if (pid.isEmpty()) {
            pid = readPid("pgrep -x cloudphone-agen", useShizuku)
        }
        return pid
    }

    private fun readPid(cmd: String, useShizuku: Boolean): String {
        val out = runCatching { execAndRead(cmd, useShizuku).trim() }.getOrDefault("")
        if (out.isEmpty()) return ""
        return HostCommands.firstPid(out)
    }

    fun stopAgent(useShizuku: Boolean) {
        val cmd = "pkill -f $AGENT_BIN_PATH; pkill cloudphone-agent; pkill cloudphone-agen; pkill -f libsys_core.so"
        try {
            exec("$cmd; true", useShizuku)
        } catch (e: Exception) {
            Log.d(TAG, "stopAgent error: ${e.message}")
        }
    }

    fun readLogsTail(useShizuku: Boolean): String {
        val logPath = agentLogPath(useShizuku)
        return try {
            val tail = execAndRead(
                "if [ -f $logPath ]; then tail -n 40 $logPath; else printf '暂无日志内容 (尚未生成日志文件)'; fi",
                useShizuku
            )
            if (tail.trim().isNotEmpty()) return tail

            val full = execAndRead("cat $logPath", useShizuku)
            if (full.trim().isEmpty()) return "暂无日志内容 (日志文件为空)"

            val lines = full.split("\n")
            val start = maxOf(0, lines.size - 40)
            lines.subList(start, lines.size).joinToString("\n")
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    private fun extractAsset(context: Context, assetName: String, outFile: File): Boolean {
        return try {
            outFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            context.assets.open(assetName).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "extractAsset failed: $assetName -> ${outFile.path}", e)
            false
        }
    }

    private fun makeWorldReadable(file: File) {
        if (file.exists()) {
            file.setReadable(true, false)
            file.setExecutable(true, false)
            if (file.isDirectory) {
                file.listFiles()?.forEach { makeWorldReadable(it) }
            }
        }
    }
}
