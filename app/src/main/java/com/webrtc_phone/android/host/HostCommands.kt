package com.webrtc_phone.android.host

/** POSIX 单引号保护配置，禁止提权 Shell 展开命令替换和变量。 */
internal object HostCommands {
    fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    fun firstPid(output: String): String = output.trim().split(Regex("\\s+"))
        .takeIf { parts -> parts.all { it.toLongOrNull()?.let { pid -> pid > 0 } == true } }
        ?.firstOrNull().orEmpty()
}
