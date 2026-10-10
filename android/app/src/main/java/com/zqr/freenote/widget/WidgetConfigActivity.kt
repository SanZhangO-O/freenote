package com.zqr.freenote.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.zqr.freenote.data.AppDatabase
import com.zqr.freenote.data.Note
import com.zqr.freenote.data.stripImageTokens
import com.zqr.freenote.ui.theme.FreenoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 桌面小部件配置：选择要固定展示的便签（随时可长按小部件重新配置）。 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED)
        setContent {
            FreenoteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WidgetConfigScreen(
                        onCancel = { finish() },
                        onPick = { noteId -> applyWidgetNote(appWidgetId, noteId) }
                    )
                }
            }
        }
    }

    private fun applyWidgetNote(appWidgetId: Int, noteId: String) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString("note_$appWidgetId", noteId)
            .apply()
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        lifecycleScope.launch {
            runCatching {
                val manager = GlanceAppWidgetManager(this@WidgetConfigActivity)
                val glanceId = manager.getGlanceIdBy(appWidgetId)
                NoteWidget().update(this@WidgetConfigActivity, glanceId)
            }
            finish()
        }
    }

    companion object {
        const val PREFS = "freenote_widget"
    }
}

@Composable
private fun WidgetConfigScreen(onCancel: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        notes = withContext(Dispatchers.IO) {
            runCatching {
                AppDatabase.getDatabase(context).noteDao().getAllActiveNotes()
            }.getOrDefault(emptyList())
        }
        loading = false
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("选择要固定在小组件上的便签", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        when {
            loading -> CircularProgressIndicator()
            notes.isEmpty() -> Text("暂无便签，先在应用内新建一条吧")
            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(notes, key = { it.id }) { note ->
                    TextButton(onClick = { onPick(note.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(note.title.ifBlank { "无标题" }, maxLines = 1)
                            Text(
                                if (note.isLocked) {
                                    "已加密便签"
                                } else {
                                    stripImageTokens(note.content, "[图片]")
                                        .lineSequence()
                                        .firstOrNull()
                                        .orEmpty()
                                        .take(30)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}
