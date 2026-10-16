package com.webrtc_phone.android.core.util

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Go time.Time JSON（RFC3339，可带纳秒）解析。minSdk 24 无 java.time，手写兼容实现。
 * Go 零值 "0001-01-01T00:00:00Z" 表示“永久/未设置”，返回 null。
 */
object Rfc3339 {

    // ThreadLocal.withInitial 需要 API 26，这里用匿名子类兼容 minSdk 24
    private val formatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
    }

    fun parse(value: String?): Long? {
        val s = value?.trim().orEmpty()
        if (s.isEmpty() || s == "null" || s.startsWith("0001-")) return null
        val tIdx = s.indexOf('T')
        if (tIdx < 0) return null
        // 拆分：日期时间主体 / 小数秒 / 时区
        var tzIdx = s.length
        for (i in s.length - 1 downTo tIdx) {
            val c = s[i]
            if (c == 'Z' || c == 'z' || c == '+' || (c == '-' && i > tIdx)) {
                tzIdx = i
                break
            }
        }
        val tz = s.substring(tzIdx).let { if (it.equals("z", true) || it.isEmpty()) "Z" else it }
        val main = s.substring(0, tzIdx)
        val dot = main.indexOf('.')
        val base = if (dot >= 0) main.substring(0, dot) else main
        val frac = if (dot >= 0) main.substring(dot + 1).filter { it.isDigit() } else ""
        val millis = (frac + "000").substring(0, 3)
        return try {
            formatter.get()!!.parse("$base.$millis$tz")?.time
        } catch (_: ParseException) {
            null
        }
    }
}
