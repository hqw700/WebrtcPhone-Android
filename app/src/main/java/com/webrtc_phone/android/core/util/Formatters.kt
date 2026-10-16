package com.webrtc_phone.android.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object Formatters {

    fun bytes(size: Long): String {
        if (size < 1024) return "$size B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = size / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun bitrate(bps: Long): String = when {
        bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        bps >= 1_000 -> String.format(Locale.US, "%.0f Kbps", bps / 1_000.0)
        else -> "$bps bps"
    }

    /** 剩余时长：3天4小时 / 2小时5分 / 12分 / 已到期 */
    fun duration(seconds: Long): String {
        if (seconds <= 0) return "已到期"
        val d = seconds / 86400
        val h = (seconds % 86400) / 3600
        val m = (seconds % 3600) / 60
        return when {
            d > 0 -> if (h > 0) "${d}天${h}小时" else "${d}天"
            h > 0 -> if (m > 0) "${h}小时${m}分" else "${h}小时"
            m > 0 -> "${m}分钟"
            else -> "${seconds}秒"
        }
    }

    fun dateTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

    fun shortDateTime(epochMs: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

    /** 相对时间：刚刚 / 5分钟前 / 3小时后 */
    fun relative(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val diff = epochMs - now
        val s = abs(diff) / 1000
        val text = when {
            s < 60 -> return "刚刚"
            s < 3600 -> "${s / 60}分钟"
            s < 86400 -> "${s / 3600}小时"
            else -> "${s / 86400}天"
        }
        return if (diff < 0) "${text}前" else "${text}后"
    }
}
