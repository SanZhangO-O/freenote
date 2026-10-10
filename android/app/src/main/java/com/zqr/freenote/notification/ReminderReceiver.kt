package com.zqr.freenote.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.room.withTransaction
import com.zqr.freenote.data.AppDatabase
import com.zqr.freenote.data.nextRecurrenceTime
import com.zqr.freenote.data.sync.SyncManager
import com.zqr.freenote.data.sync.SyncStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getStringExtra("note_id") ?: return
        val title = intent.getStringExtra("title").orEmpty()
        val content = intent.getStringExtra("content").orEmpty()
        NotificationHelper.showReminder(context, noteId, title, content)

        // 重复提醒：到点后计算下一次并重排。
        // 数据库访问放 goAsync + IO，避免主线程阻塞广播超时（ANR）。
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = AppDatabase.getDatabase(context)
                val note = database.noteDao().getNoteById(noteId) ?: return@launch
                if (note.isDeleted) return@launch
                val next = nextRecurrenceTime(System.currentTimeMillis(), note.recurrence)
                    ?: return@launch
                database.withTransaction {
                    database.noteDao().setReminder(noteId, next)
                    SyncStore(database).enqueue(noteId)
                }
                // 关键：把下一次闹钟真正排进 AlarmManager，否则重复提醒只会响一次
                ReminderScheduler.schedule(
                    context,
                    noteId,
                    reminderDisplayTitle(note),
                    reminderDisplayContent(note),
                    next
                )
                SyncManager.notifyLocalChange()
            } finally {
                pending.finish()
            }
        }
    }
}
