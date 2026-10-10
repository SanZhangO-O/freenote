package com.zqr.freenote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TimeFormatTest {

    @Test
    fun formatsNoteTimes() {
        // 跨年带年份，同年只有月日时间
        val cal = Calendar.getInstance()
        cal.set(2000, Calendar.JANUARY, 1, 9, 5, 0)
        assertTrue(TimeFormat.noteTime(cal.timeInMillis).startsWith("2000/01/01 09:05"))

        val now = System.currentTimeMillis()
        val text = TimeFormat.noteTime(now)
        assertTrue(Regex("""^\d{2}/\d{2} \d{2}:\d{2}$""").matches(text))
        assertEquals(text, TimeFormat.noteTime(now))
    }
}
