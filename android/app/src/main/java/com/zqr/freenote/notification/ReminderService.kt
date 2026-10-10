package com.zqr.freenote.notification

import android.content.Context
import com.zqr.freenote.data.Note
import com.zqr.freenote.data.NoteRepository
import com.zqr.freenote.data.nextRecurrenceTime
import com.zqr.freenote.data.stripImageTokens

class ReminderService(
    private val context: Context,
    private val repository: NoteRepository
) {
    suspend fun sync(note: Note) {
        val time = note.reminderTime
        if (time == null) {
            // 提醒被清除时也必须取消旧闹钟，否则到点仍会弹出过期提醒
            ReminderScheduler.cancel(context, note.id)
            return
        }
        if (time > System.currentTimeMillis()) {
            ReminderScheduler.schedule(context, note.id, displayTitle(note), displayContent(note), time)
        } else {
            advanceOrClear(note)
        }
    }

    fun cancel(noteId: String) {
        ReminderScheduler.cancel(context, noteId)
    }

    suspend fun rescheduleAll() {
        repository.getNotesWithReminder().forEach { note ->
            note.reminderTime?.let { time ->
                if (time > System.currentTimeMillis()) {
                    ReminderScheduler.schedule(context, note.id, displayTitle(note), displayContent(note), time)
                } else {
                    // 已过期（重启错过闹钟/系统时间回调）：重复提醒推进到下一次，
                    // 不重复的才清空，避免陈旧数据残留
                    advanceOrClear(note)
                }
            }
        }
    }

    /** 已过期提醒：有重复规则则推进到下一次并重排闹钟，否则清空。 */
    private suspend fun advanceOrClear(note: Note) {
        val next = nextRecurrenceTime(System.currentTimeMillis(), note.recurrence)
        if (next != null) {
            repository.setReminder(note.id, next)
            ReminderScheduler.schedule(context, note.id, displayTitle(note), displayContent(note), next)
        } else {
            ReminderScheduler.cancel(context, note.id)
            repository.setReminder(note.id, null)
        }
    }

    private fun displayTitle(note: Note): String = reminderDisplayTitle(note)

    private fun displayContent(note: Note): String = reminderDisplayContent(note)
}

/** 提醒通知标题：锁定便签不泄露标题与正文。 */
fun reminderDisplayTitle(note: Note): String =
    if (note.isLocked) "加密便签提醒" else note.title

/** 提醒通知正文：锁定便签不泄露内容；图片标记替换为占位文字。 */
fun reminderDisplayContent(note: Note): String =
    if (note.isLocked) "" else stripImageTokens(note.content, "[图片]")
