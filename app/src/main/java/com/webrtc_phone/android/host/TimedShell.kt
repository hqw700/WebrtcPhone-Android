package com.webrtc_phone.android.host

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock

internal data class ShellResult(val exitCode: Int, val output: String)

/** 阻塞式 Process API 的截止时间保护；销毁不等待命令锁，也不向卡住的 stdin 写 exit。 */
internal object ProcessDeadline {
    private val timer = Executors.newScheduledThreadPool(2) { task ->
        Thread(task, "host-shell-timeout").apply { isDaemon = true }
    }
    fun guard(process: Process, timeoutMs: Long) = timer.schedule(
        { runCatching { process.destroy() } }, timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS
    )
    fun alive(process: Process): Boolean = try { process.exitValue(); false } catch (_: IllegalThreadStateException) { true }

    fun run(process: Process, timeoutMs: Long = 15_000): ShellResult {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val alarm = guard(process, timeoutMs)
        val output = ByteArrayOutputStream()
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted || System.nanoTime() >= end) throw TimeoutException("Shell 命令超时")
                drain(process.inputStream, output)
                drain(process.errorStream, output)
                if (!alive(process)) {
                    drain(process.inputStream, output)
                    drain(process.errorStream, output)
                    return ShellResult(process.exitValue(), output.toString("UTF-8"))
                }
                Thread.sleep(10)
            }
        } finally {
            alarm.cancel(false)
            process.destroy()
        }
    }

    fun drain(input: java.io.InputStream, output: ByteArrayOutputStream) {
        while (input.available() > 0) {
            val bytes = ByteArray(minOf(input.available(), 8192))
            val count = input.read(bytes)
            if (count <= 0) return
            if (output.size() + count > 1024 * 1024) throw IOException("Shell 输出超过 1 MiB，请缩小命令输出范围")
            output.write(bytes, 0, count)
        }
    }
}

internal class TimedShell(private val open: () -> Process) {
    private val lock = ReentrantLock()
    @Volatile private var process: Process? = null
    private var sequence = 0L
    fun isAlive(): Boolean = process?.let(ProcessDeadline::alive) == true
    fun close() { process?.let { process = null; it.destroy() } }

    fun execute(command: String, timeoutMs: Long = 15_000): ShellResult {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        if (!lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) throw TimeoutException("Shell 正忙，等待超时")
        try {
            val remaining = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())
            if (remaining <= 0) throw TimeoutException("Shell 命令超时")
            val current = process?.takeIf(ProcessDeadline::alive) ?: open().also { process = it }
            val alarm = ProcessDeadline.guard(current, remaining)
            try {
                val marker = "__CP_END_${++sequence}_${System.nanoTime()}__:"
                // marker 单独占一行，printf 等不带换行的输出也能正确结束。
                current.outputStream.write("$command\nprintf '\\n$marker%s\\n' \$?\n".toByteArray(Charsets.UTF_8))
                current.outputStream.flush()
                val output = ByteArrayOutputStream()
                while (true) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() >= end) throw TimeoutException("Shell 命令超时")
                    ProcessDeadline.drain(current.inputStream, output)
                    val text = output.toString("UTF-8")
                    val index = text.indexOf("\n$marker")
                    if (index >= 0) {
                        val code = text.substring(index + marker.length + 1).substringBefore('\n')
                        if (text.substring(index + marker.length + 1).contains('\n')) {
                            return ShellResult(code.trim().toInt(), text.substring(0, index))
                        }
                    }
                    if (!ProcessDeadline.alive(current)) throw IOException("Shell 已退出或授权被拒绝")
                    Thread.sleep(10)
                }
            } finally { alarm.cancel(false) }
        } catch (error: Exception) {
            close()
            if (System.nanoTime() >= end) throw TimeoutException("Shell 命令超时")
            throw error
        } finally { lock.unlock() }
    }
}
