package com.webrtc_phone.android.host

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

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

    data class CommandResult(val exitCode: Int, val output: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * 常驻交互式 Root Shell 会话：
     * 维持单一持久化的 su 进程，避免每次执行命令 fork 新进程引起授权通知频繁弹出。
     */
    object RootShell {
        private var process: Process? = null
        private var writer: BufferedWriter? = null
        private var reader: BufferedReader? = null
        private var tokenSeq: Long = 0
        private val lock = Any()

        fun isAlive(): Boolean {
            synchronized(lock) {
                val p = process ?: return false
                return try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        p.isAlive
                    } else {
                        p.exitValue()
                        false
                    }
                } catch (_: IllegalThreadStateException) {
                    true
                }
            }
        }

        fun close() {
            synchronized(lock) {
                try {
                    writer?.write("exit\n")
                    writer?.flush()
                } catch (_: Throwable) {}
                try {
                    process?.destroy()
                } catch (_: Throwable) {}
                process = null
                writer = null
                reader = null
            }
        }

        private fun openInternal(): Boolean {
            close()
            return try {
                val pb = ProcessBuilder("su")
                pb.redirectErrorStream(true)
                val p = pb.start()
                process = p
                writer = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))
                reader = BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8))
                true
            } catch (t: Throwable) {
                Log.w(TAG, "RootShell open failed: ${t.message}")
                close()
                false
            }
        }

        fun execute(cmd: String): CommandResult {
            synchronized(lock) {
                if (!isAlive()) {
                    if (!openInternal()) {
                        return CommandResult(-1, "无法打开 Root Shell")
                    }
                }
                val token = "__CP_END_${System.currentTimeMillis()}_${++tokenSeq}__"
                val w = writer ?: return CommandResult(-1, "RootShell writer 为空")
                val r = reader ?: return CommandResult(-1, "RootShell reader 为空")

                return try {
                    w.write("$cmd\necho \"$token\" $?\n")
                    w.flush()

                    val output = StringBuilder()
                    var line: String?
                    var exitCode = -1
                    var found = false

                    while (r.readLine().also { line = it } != null) {
                        val current = line ?: break
                        if (current.startsWith(token)) {
                            found = true
                            val codeStr = current.substring(token.length).trim()
                            exitCode = codeStr.toIntOrNull() ?: -1
                            break
                        }
                        output.append(current).append("\n")
                    }

                    if (!found) {
                        close()
                        CommandResult(-1, output.toString())
                    } else {
                        CommandResult(exitCode, output.toString())
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "RootShell IO error: ${e.message}")
                    close()
                    CommandResult(-1, "IO 错误: ${e.message}")
                }
            }
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
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            reader.readLine() != null
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

    private fun createCmdProcess(cmd: String, useShizuku: Boolean): Process {
        if (useShizuku) {
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
                method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            } catch (e: Exception) {
                throw IOException("Shizuku 进程启动失败: ${e.message}", e)
            }
        } else {
            return Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        }
    }

    fun exec(cmd: String, useShizuku: Boolean) {
        if (useShizuku) {
            val process = createCmdProcess(cmd, true)
            process.waitFor()
        } else {
            val res = RootShell.execute(cmd)
            if (!res.isSuccess) {
                Log.d(TAG, "RootShell exec failed [${res.exitCode}]: $cmd -> ${res.output}")
            }
        }
    }

    fun execAndRead(cmd: String, useShizuku: Boolean): String {
        if (useShizuku) {
            var process: Process? = null
            return try {
                process = createCmdProcess(cmd, true)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append("\n")
                }
                sb.toString()
            } catch (e: Exception) {
                Log.d(TAG, "execAndRead error: ${e.message}")
                ""
            } finally {
                process?.destroy()
            }
        } else {
            return RootShell.execute(cmd).output
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

    fun buildAgentStartCommand(signaling: String, deviceId: String, iceServers: String): String {
        val sb = StringBuilder()
        // CGroup 脱壳保护
        sb.append("for cg in /sys/fs/cgroup /acct /dev/cpuset /dev/cpuctl; do ")
            .append("[ -f \"\$cg/cgroup.procs\" ] && echo \$\$ > \"\$cg/cgroup.procs\" 2>/dev/null || true; ")
            .append("done; ")

        sb.append("export CP_AGENT_JAR=$LIBSYS_PATH; ")

        val sig = signaling.trim()
        if (sig.isNotEmpty()) {
            sb.append("export CP_AGENT_SIGNALING=\"$sig\"; ")
        }

        // double-fork + setsid + nohup 切断管道绑定
        sb.append("(setsid nohup env GODEBUG=asyncpreemptoff=1 $AGENT_BIN_PATH ")
        if (sig.isNotEmpty()) {
            sb.append("-signaling \"$sig\" ")
        }
        val id = deviceId.trim()
        if (id.isNotEmpty()) {
            sb.append("-id \"$id\" ")
        }
        val ice = iceServers.trim()
        if (ice.isNotEmpty()) {
            sb.append("-ice-servers \"$ice\" ")
        }
        sb.append("< /dev/null >> $AGENT_LOG_PATH 2>&1 &)")
        return sb.toString()
    }

    fun startAgent(context: Context, useShizuku: Boolean, signaling: String, deviceId: String, iceServers: String) {
        deployBinaries(context, useShizuku)
        val cmd = buildAgentStartCommand(signaling, deviceId, iceServers)
        Log.i(TAG, "Starting agent: $cmd")
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
        val out = execAndRead(cmd, useShizuku).trim()
        if (out.isEmpty()) return ""
        return out.split(Regex("[\\s\\n]+")).firstOrNull() ?: ""
    }

    fun stopAgent(useShizuku: Boolean) {
        val cmd = "pkill -f $AGENT_BIN_PATH; pkill cloudphone-agent; pkill cloudphone-agen; pkill -f libsys_core.so"
        try {
            exec(cmd, useShizuku)
        } catch (e: Exception) {
            Log.d(TAG, "stopAgent error: ${e.message}")
        }
    }

    fun readLogsTail(useShizuku: Boolean): String {
        return try {
            val tail = execAndRead("tail -n 40 $AGENT_LOG_PATH", useShizuku)
            if (tail.trim().isNotEmpty()) return tail

            val full = execAndRead("cat $AGENT_LOG_PATH", useShizuku)
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
