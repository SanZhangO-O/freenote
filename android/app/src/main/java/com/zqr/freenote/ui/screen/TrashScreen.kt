package com.zqr.freenote.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zqr.freenote.data.Note
import com.zqr.freenote.data.NoteColor
import com.zqr.freenote.data.stripImageTokens
import com.zqr.freenote.ui.common.themedColor
import com.zqr.freenote.util.TimeFormat
import com.zqr.freenote.viewmodel.NoteViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    viewModel: NoteViewModel,
    onNavigateBack: () -> Unit
) {
    val notes by viewModel.trashedNotes.collectAsState()
    var noteToAction by remember { mutableStateOf<Note?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (notes.isNotEmpty()) {
                        IconButton(onClick = { confirmEmpty = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "清空")
                        }
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("回收站为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(notes, key = { it.id }) { note ->
                    TrashCard(
                        note = note,
                        onRestore = { viewModel.restore(note) },
                        onDelete = { noteToAction = note }
                    )
                }
            }
        }
    }

    noteToAction?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToAction = null },
            title = { Text("永久删除") },
            text = { Text("此操作不可恢复，确定彻底删除该便签吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePermanently(note)
                    noteToAction = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { noteToAction = null }) { Text("取消") } }
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("清空回收站") },
            text = { Text("将永久删除回收站中的全部 ${notes.size} 条便签，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.emptyTrash()
                    confirmEmpty = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun TrashCard(
    note: Note,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = NoteColor.themedColor(note.color, MaterialTheme.colorScheme)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (note.title.isNotEmpty() || note.isLocked) {
                    Text(
                        if (note.isLocked) "已加密便签" else note.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!note.isLocked && note.content.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        stripImageTokens(note.content, "[图片]"),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = TimeFormat.noteTime(note.deletedAt ?: note.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onRestore) {
                Icon(Icons.Filled.Restore, contentDescription = "恢复")
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete) {
                // 与「恢复」拉开间距并用错误色区分：两枚同款图标相邻时易点错永久删除
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "彻底删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
