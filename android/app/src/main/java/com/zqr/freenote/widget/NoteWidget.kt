package com.zqr.freenote.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.compose.ui.unit.dp
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.zqr.freenote.MainActivity
import com.zqr.freenote.data.AppDatabase
import com.zqr.freenote.data.Note
import com.zqr.freenote.data.stripImageTokens
import com.zqr.freenote.notification.NotificationHelper
import kotlinx.coroutines.flow.first

class NoteWidget : GlanceAppWidget() {

    private val openNoteKey = ActionParameters.Key<String>(NotificationHelper.EXTRA_NOTE_ID)
    private val openNewKey = ActionParameters.Key<Boolean>(MainActivity.EXTRA_OPEN_NEW)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 单便签模式：配置 Activity 选定的便签固定展示；未配置时回退为最近 4 条
        val pinned = pinnedNote(context, id)
        if (pinned != null) {
            val body = if (pinned.isLocked) {
                "已加密便签（点击打开并解锁查看）"
            } else {
                stripImageTokens(pinned.content, "[图片]").ifBlank { "（空白便签）" }
            }
            provideContent {
                GlanceTheme {
                    Column(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .background(GlanceTheme.colors.surface)
                            .cornerRadius(16.dp)
                            .clickable(
                                actionStartActivity<MainActivity>(
                                    actionParametersOf(openNoteKey to pinned.id)
                                )
                            )
                    ) {
                        Text(
                            if (pinned.isLocked) "已加密便签" else pinned.title.ifBlank { "无标题" },
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                        Spacer(GlanceModifier.height(8.dp))
                        Text(
                            body,
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                            maxLines = 12
                        )
                    }
                }
            }
            return
        }

        // 固定高度内展示 4 条 + 新建入口，避免内容溢出被裁剪
        val notes = AppDatabase.getDatabase(context).noteDao().getActiveNotes().first().take(4)

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(16.dp)
                ) {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Horizontal.Start
                    ) {
                        Text(
                            "FreeNote",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    if (notes.isEmpty()) {
                        Text(
                            "点击新建便签",
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                            modifier = GlanceModifier.clickable(
                                actionStartActivity<MainActivity>(
                                    actionParametersOf(openNewKey to true)
                                )
                            )
                        )
                    } else {
                        notes.forEach { note ->
                            Column(
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable(
                                        actionStartActivity<MainActivity>(
                                            actionParametersOf(openNoteKey to note.id)
                                        )
                                    )
                            ) {
                                Text(
                                    if (note.isLocked) "已加密便签" else note.title.ifBlank { "无标题" },
                                    style = TextStyle(
                                        color = GlanceTheme.colors.onSurface,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                        Spacer(GlanceModifier.height(4.dp))
                        Text(
                            "＋ 新建便签",
                            style = TextStyle(
                                color = GlanceTheme.colors.primary,
                                fontWeight = FontWeight.Medium
                            ),
                            modifier = GlanceModifier.clickable(
                                actionStartActivity<MainActivity>(
                                    actionParametersOf(openNewKey to true)
                                )
                            )
                        )
                    }
                }
            }
        }
    }
}

class NoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NoteWidget()
}

/** 读取配置 Activity 为该小部件固定的便签；未配置/已删除返回 null（回退最近列表）。 */
private suspend fun pinnedNote(context: Context, id: GlanceId): Note? {
    val appWidgetId = runCatching {
        GlanceAppWidgetManager(context).getAppWidgetId(id)
    }.getOrNull() ?: return null
    val noteId = context
        .getSharedPreferences(WidgetConfigActivity.PREFS, Context.MODE_PRIVATE)
        .getString("note_$appWidgetId", null)
        ?: return null
    val note = runCatching {
        AppDatabase.getDatabase(context).noteDao().getNoteById(noteId)
    }.getOrNull()
    return note?.takeIf { !it.isDeleted }
}
