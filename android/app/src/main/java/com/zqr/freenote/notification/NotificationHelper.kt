package com.zqr.freenote.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.zqr.freenote.MainActivity
import com.zqr.freenote.R
import com.zqr.freenote.util.StableIds

object NotificationHelper {
    const val CHANNEL_ID = "note_reminders"
    const val CHANNEL_NAME = "便签提醒"
    const val EXTRA_NOTE_ID = "com.zqr.freenote.extra.NOTE_ID"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // minSdk 26，NotificationChannel 必然可用
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "便签定时提醒"
        }
        manager.createNotificationChannel(channel)
    }

    fun showReminder(context: Context, noteId: String, title: String, content: String) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 点击通知直接打开对应便签（锁定便签会先进入解锁流程）
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_NOTE_ID, noteId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            StableIds.forNote(noteId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(if (title.isBlank()) "便签提醒" else title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(StableIds.forNote(noteId), notification)
    }
}
