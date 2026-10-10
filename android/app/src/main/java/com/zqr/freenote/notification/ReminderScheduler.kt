package com.zqr.freenote.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zqr.freenote.util.StableIds

object ReminderScheduler {

    private const val EXTRA_NOTE_ID = "note_id"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_CONTENT = "content"

    fun schedule(context: Context, noteId: String, title: String, content: String, time: Long) {
        if (time <= System.currentTimeMillis()) return
        val intent = createIntent(context, noteId, title, content)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, StableIds.forNote(noteId), intent, flags)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent)
        } else {
            // 检查与调用之间精确闹钟权限可能被撤销，SecurityException 时降级为非精确
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent)
            } catch (e: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent)
            }
        }
    }

    fun cancel(context: Context, noteId: String) {
        val intent = createIntent(context, noteId, "", "")
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, StableIds.forNote(noteId), intent, flags)
        if (pendingIntent != null) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun createIntent(context: Context, noteId: String, title: String, content: String): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_NOTE_ID, noteId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_CONTENT, content)
        }
}
