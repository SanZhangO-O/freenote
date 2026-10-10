package com.zqr.freenote.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zqr.freenote.data.AppDatabase
import com.zqr.freenote.data.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.MY_PACKAGE_REPLACED" &&
            intent.action != Intent.ACTION_TIME_CHANGED &&
            intent.action != Intent.ACTION_TIMEZONE_CHANGED
        ) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(context).noteDao()
                ReminderService(context, NoteRepository(dao)).rescheduleAll()
            } catch (e: Throwable) {
                // 数据库损坏等异常不使进程崩溃，仅记录后正常结束
                Log.w(TAG, "rescheduleAll failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
