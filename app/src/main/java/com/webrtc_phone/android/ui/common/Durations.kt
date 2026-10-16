package com.webrtc_phone.android.ui.common

/** 时长选项（分享/账号有效期等），0 = 永久 */
data class DurationOption(val label: String, val seconds: Long)

object Durations {
    const val HOUR = 3600L
    const val DAY = 86400L

    val shareExpiry = listOf(
        DurationOption("1 小时", HOUR),
        DurationOption("6 小时", 6 * HOUR),
        DurationOption("1 天", DAY),
        DurationOption("7 天", 7 * DAY),
        DurationOption("30 天", 30 * DAY),
        DurationOption("永久", 0),
    )

    val extend = listOf(
        DurationOption("+1 小时", HOUR),
        DurationOption("+1 天", DAY),
        DurationOption("+7 天", 7 * DAY),
        DurationOption("+30 天", 30 * DAY),
    )

    val account = listOf(
        DurationOption("永久", 0),
        DurationOption("1 天", DAY),
        DurationOption("7 天", 7 * DAY),
        DurationOption("30 天", 30 * DAY),
        DurationOption("90 天", 90 * DAY),
        DurationOption("365 天", 365 * DAY),
    )
}
