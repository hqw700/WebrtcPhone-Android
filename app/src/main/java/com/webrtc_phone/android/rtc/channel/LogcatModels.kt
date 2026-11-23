package com.webrtc_phone.android.rtc.channel

/** logcat 优先级，顺序与 Android 日志级别一致。 */
enum class LogcatLevel(val code: Char, val label: String, val rank: Int) {
    VERBOSE('V', "Verbose", 0),
    DEBUG('D', "Debug", 1),
    INFO('I', "Info", 2),
    WARN('W', "Warn", 3),
    ERROR('E', "Error", 4),
    FATAL('F', "Fatal", 5),
    UNKNOWN('?', "Other", -1);

    companion object {
        fun from(code: Char): LogcatLevel = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class LogcatLine(
    val raw: String,
    val timestamp: String = "",
    val pid: Int? = null,
    val tid: Int? = null,
    val level: LogcatLevel = LogcatLevel.UNKNOWN,
    val tag: String = "",
    val message: String = raw,
)

data class LogcatFilter(
    val query: String = "",
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val minimumLevel: LogcatLevel = LogcatLevel.VERBOSE,
    val pid: Int? = null,
    val includedTags: Set<String> = emptySet(),
    val excludedTags: Set<String> = emptySet(),
) {
    private val compiledQuery: Regex? = if (regex && query.isNotBlank()) runCatching {
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        Regex(query, options)
    }.getOrNull() else null

    fun matches(line: LogcatLine): Boolean {
        if (line.level != LogcatLevel.UNKNOWN && line.level.rank < minimumLevel.rank) return false
        if (pid != null && line.pid != pid) return false
        if (includedTags.isNotEmpty() && includedTags.none { it.equals(line.tag, ignoreCase = true) }) return false
        if (excludedTags.any { it.equals(line.tag, ignoreCase = true) }) return false
        if (query.isBlank()) return true
        return if (regex) {
            compiledQuery?.containsMatchIn(line.raw) == true
        } else {
            line.raw.contains(query, ignoreCase = !caseSensitive)
        }
    }
}

/** 解析 `logcat -v threadtime`，同时兼容 brief 格式和 shell 返回的普通文本。 */
object LogcatParser {
    private val ansi = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
    private val threadTime = Regex(
        "^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+(.+?):\\s?(.*)$"
    )
    private val brief = Regex("^([VDIWEF])/([^ (]+)\\(\\s*(\\d+)\\):\\s?(.*)$")

    fun parse(value: String): LogcatLine {
        val raw = value.replace("\r", "").replace(ansi, "")
        threadTime.matchEntire(raw)?.let { m ->
            return LogcatLine(
                raw = raw,
                timestamp = m.groupValues[1],
                pid = m.groupValues[2].toIntOrNull(),
                tid = m.groupValues[3].toIntOrNull(),
                level = LogcatLevel.from(m.groupValues[4].first()),
                tag = m.groupValues[5].trim(),
                message = m.groupValues[6],
            )
        }
        brief.matchEntire(raw)?.let { m ->
            return LogcatLine(
                raw = raw,
                pid = m.groupValues[3].toIntOrNull(),
                level = LogcatLevel.from(m.groupValues[1].first()),
                tag = m.groupValues[2],
                message = m.groupValues[4],
            )
        }
        return LogcatLine(raw = raw)
    }
}
