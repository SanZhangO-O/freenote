package com.zqr.freenote.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 便签列表时间的统一格式化：同年显示 "MM/dd HH:mm"，跨年自动补年份，
 * 避免去年 12 月的便签与今年 12 月混淆。
 */
object TimeFormat {
    // DateTimeFormatter 线程安全且不可变，可在卡片组合中直接复用，避免每次 new
    private val WITH_YEAR = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.getDefault())
    private val WITHOUT_YEAR = DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.getDefault())
    private val ZONE = ZoneId.systemDefault()

    fun noteTime(millis: Long): String {
        val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZONE)
        val formatter = if (date.year != LocalDate.now().year) WITH_YEAR else WITHOUT_YEAR
        return formatter.format(date)
    }
}
