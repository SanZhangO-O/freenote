package com.zqr.freenote.ui.screen

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.zqr.freenote.data.NoteColor
import com.zqr.freenote.data.NOTE_TEMPLATES
import com.zqr.freenote.data.RECURRENCE_CHOICES
import com.zqr.freenote.data.SharedIntake
import com.zqr.freenote.data.folderInSubtree
import com.zqr.freenote.data.folderParent
import com.zqr.freenote.data.stripImageTokens
import com.zqr.freenote.ui.common.themedColor
import com.zqr.freenote.ui.components.ChecklistEditor
import com.zqr.freenote.ui.components.ColorPicker
import com.zqr.freenote.ui.components.FolderPickerDialog
import com.zqr.freenote.ui.components.InlineNoteEditor
import com.zqr.freenote.ui.components.MarkdownText
import com.zqr.freenote.ui.components.ImageViewerDialog
import com.zqr.freenote.ui.components.NoteEditLockedIndicator
import com.zqr.freenote.ui.components.NoteEditLockPinDialog
import com.zqr.freenote.ui.components.NoteEditReminderRow
import com.zqr.freenote.ui.components.NoteEditTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteEditScreen(
    noteId: String?,
    editViewModel: NoteEditViewModel,
    onNavigateBack: () -> Unit,
    onUnlockRequested: () -> Unit,
    onNavigateToEdit: (String) -> Unit
) {
    val state = editViewModel
    val context = LocalContext.current
    val folderPaths by editViewModel.availableFolders.collectAsState()
    var showFolderPicker by remember { mutableStateOf(false) }

    // 保存完成后的导航回调由界面挂载：旋转重建后仍指向当前 NavController
    DisposableEffect(Unit) {
        state.attachSavedCallback(onNavigateBack)
        onDispose { state.attachSavedCallback(null) }
    }

    // 系统返回（手势/物理键）：有更改时自动保存后返回，
    // 仅锁定便签会话过期无法保存时才弹确认，且会清理会话 PIN
    BackHandler { state.requestBack(onNavigateBack) }

    // 退到后台（含旋转）时立即落库，防止进程被杀丢失未保存内容
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        state.saveOnStop()
    }

    // 系统分享（ACTION_SEND）：新建便签页实时接收。
    // 页面前台打开时分享会追加到当前草稿（避免内容滞留或草稿被覆盖丢失），
    // 分享后新开的页面由首次组合直接消费。
    if (noteId == null) {
        val sharedIntake by SharedIntake.pending.collectAsState()
        LaunchedEffect(sharedIntake) {
            val item = SharedIntake.consume() ?: return@LaunchedEffect
            state.applySharedText(item.text, item.subject)
        }
    }

    val noteColor = NoteColor.themedColor(state.selectedColor, MaterialTheme.colorScheme)
    val animatedColor by animateColorAsState(targetValue = noteColor, label = "noteBgColor")
    val dateFormatter = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> state.insertImages(uris) }

    // 点按图片打开的全屏查看器
    var viewingImagePath by rememberSaveable { mutableStateOf<String?>(null) }

    // 待确认移除的图片块：✕ 与「点按看图」区域相邻，误触会立即删图并自动保存，需二次确认
    var removingImageBlockId by rememberSaveable { mutableStateOf<String?>(null) }

    // 通知权限被拒绝时的说明对话框（附去系统设置入口）
    var showNotifDeniedDialog by remember { mutableStateOf(false) }

    // 导出为 Markdown（SAF）：先记住待写入的文本，创建文件成功后写入
    var pendingExportText by remember { mutableStateOf<String?>(null) }
    val exportMarkdownLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val text = pendingExportText
        pendingExportText = null
        if (uri != null && text != null) {
            val ok = runCatching {
                val stream = context.contentResolver.openOutputStream(uri)
                if (stream == null) {
                    false
                } else {
                    stream.use { out -> out.write(text.toByteArray()) }
                    true
                }
            }.getOrDefault(false)
            Toast.makeText(
                context,
                if (ok) "已导出 Markdown 文件" else "导出失败，请重试",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun shareTextNow() {
        val title = state.title.trim().ifBlank { "无标题便签" }
        val body = stripImageTokens(state.content, "[图片]").trim()
        val text = if (body.isBlank()) title else "$title\n\n$body"
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, title)
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, "分享便签")
        )
    }

    fun exportMarkdownNow() {
        val title = state.title.trim().ifBlank { "无标题便签" }
        val body = stripImageTokens(state.content, "[图片]")
        pendingExportText = "# $title\n\n$body\n"
        exportMarkdownLauncher.launch("$title.md")
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            state.pickReminder(context)
        } else {
            // 被拒绝（含永久拒绝）后系统不再弹窗：给出可操作的去设置入口
            showNotifDeniedDialog = true
        }
    }

    Scaffold(
        topBar = {
            NoteEditTopBar(
                screenTitle = if (noteId == null) "新建便签" else "编辑便签",
                animatedColor = animatedColor,
                isPinned = state.isPinned,
                menuExpanded = state.menuExpanded,
                isChecklist = state.isChecklist,
                reminderTime = state.reminderTime,
                isLocked = state.isLocked,
                previewMode = state.previewMode,
                interactive = !state.needsUnlock && !state.decryptFailed,
                onBack = { state.requestBack(onNavigateBack) },
                onTogglePin = { state.togglePin() },
                onMenuExpandedChange = { state.onMenuExpandedChange(it) },
                onToggleChecklist = { state.toggleChecklist() },
                onPickReminder = {
                    state.pickReminder(context) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onClearReminder = { state.clearReminder() },
                onLockToggled = { state.requestUnlock() },
                onTogglePreview = { state.togglePreview() },
                onShareText = { shareTextNow() },
                onExportMarkdown = { exportMarkdownNow() },
                onShowHistory = { state.openHistory() },
                onInsertTemplate = { state.openTemplateDialog() },
                onSave = { state.save() }
            )
        },
        containerColor = animatedColor
    ) { paddingValues ->
        if (state.needsUnlock) {
            LockedEditPlaceholder(
                onUnlock = onUnlockRequested,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(animatedColor)
            )
            return@Scaffold
        }
        if (state.decryptFailed) {
            DecryptFailedPlaceholder(
                onBack = { state.requestBack(onNavigateBack) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(animatedColor)
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                // 键盘弹出时把内容垫到输入法之上（edge-to-edge 下 adjustResize 不再缩窗口），
                // 否则插入图片后正文变长，光标会被键盘挡住，表现为无法继续输入
                .consumeWindowInsets(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .background(animatedColor)
        ) {
            if (state.sessionExpired) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "解锁已过期，改动尚未保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { state.save() }) { Text("验证并保存") }
                }
            }
            ColorPicker(selectedColor = state.selectedColor, onColorSelected = { state.onColorSelected(it) })

            if (state.reminderTime != null) {
                NoteEditReminderRow(
                    formattedTime = dateFormatter.format(Date(state.reminderTime!!)),
                    onClear = { state.clearReminder() }
                )
            }

            if (state.isLocked) {
                NoteEditLockedIndicator()
            }

            // 插入图片：所选图片插入光标所在位置（在光标处切分文字）
            if (!state.isChecklist && !state.previewMode) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    TextButton(onClick = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("插入图片", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "图片插入在光标处；点按可查看大图，长按可拖动调整位置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }

            TagInputRow(tags = state.tags, onTagsChanged = state::scheduleAutoSave)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showFolderPicker = true }) {
                    Text(
                        if (state.folder.isEmpty()) "📁 未分类" else "📁 ${state.folder}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            OutlinedTextField(
                value = state.title,
                onValueChange = { state.onTitleChange(it) },
                placeholder = { Text("标题", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                textStyle = TextStyle(fontSize = 20.sp),
                colors = transparentFieldColors()
            )

            Spacer(Modifier.height(8.dp))

            if (state.isChecklist) {
                ChecklistEditor(
                    content = state.content,
                    onContentChange = { state.onContentChange(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            } else if (state.previewMode) {
                MarkdownText(
                    text = state.content.ifBlank { "（无内容预览）" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    onWikiLink = { title -> state.openWikiLink(title) { id -> onNavigateToEdit(id) } }
                )
            } else {
                InlineNoteEditor(
                    blocks = state.blocks,
                    focusBlockId = state.pendingFocusBlockId,
                    onTextChange = state::updateTextBlock,
                    onFocusBlock = state::onBlockFocused,
                    onFocusConsumed = state::consumePendingFocus,
                    onRemoveImage = { removingImageBlockId = it },
                    onOpenImage = { viewingImagePath = it },
                    onMoveBlock = state::moveBlock,
                    onReorderFinished = state::commitBlockOrder,
                    onBackspaceAtStart = state::backspaceAtBlockStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
            if (!state.isChecklist) {
                val charCount = state.content.count { !it.isWhitespace() }
                if (charCount > 0) {
                    Text(
                        "$charCount 字",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.showTemplateDialog) {
        AlertDialog(
            onDismissRequest = { state.dismissTemplateDialog() },
            title = { Text("插入模板") },
            text = {
                Column {
                    NOTE_TEMPLATES.forEach { (name, body) ->
                        TextButton(
                            onClick = { state.insertTemplate(body) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { state.dismissTemplateDialog() }) { Text("取消") }
            }
        )
    }

    if (state.showLockPinDialog) {
        NoteEditLockPinDialog(
            pinInput = state.pinInput,
            pinError = state.pinError,
            onPinInputChange = { state.onPinInputChange(it) },
            onConfirm = { state.confirmLockPin() },
            onDismiss = { state.dismissLockPinDialog() }
        )
    }

    if (state.showReauthDialog) {
        NoteEditLockPinDialog(
            pinInput = state.reauthPinInput,
            pinError = state.reauthPinError,
            title = "会话已过期，请重新输入 PIN",
            onPinInputChange = { state.onReauthPinInputChange(it) },
            onConfirm = { state.confirmReauth() },
            onDismiss = { state.dismissReauth() }
        )
    }

    if (state.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { state.cancelDiscard() },
            title = { Text("放弃更改？") },
            text = { Text("会话已过期，无法自动保存锁定便签的更改；返回后本次更改将丢失。") },
            confirmButton = {
                TextButton(onClick = { state.confirmDiscard(onNavigateBack) }) {
                    Text("放弃", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { state.cancelDiscard() }) { Text("继续编辑") }
            }
        )
    }

    if (state.showUnlockConfirmDialog) {
        AlertDialog(
            onDismissRequest = { state.dismissUnlockConfirm() },
            title = { Text("解除锁定") },
            text = { Text("解除后便签将以明文保存，确定解除锁定吗？") },
            confirmButton = {
                TextButton(onClick = { state.confirmUnlock() }) {
                    Text("解除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { state.dismissUnlockConfirm() }) { Text("取消") }
            }
        )
    }

    if (state.showChecklistConvertDialog) {
        AlertDialog(
            onDismissRequest = { state.dismissChecklistConvert() },
            title = { Text("切换为清单？") },
            text = {
                Text("转换会移除正文里的 Markdown 标记（#、**、- 等），并且会自动保存，无法撤销。")
            },
            confirmButton = {
                TextButton(onClick = { state.confirmChecklistConvert() }) { Text("转换") }
            },
            dismissButton = {
                TextButton(onClick = { state.dismissChecklistConvert() }) { Text("取消") }
            }
        )
    }

    if (state.showRecurrenceDialog) {
        AlertDialog(
            onDismissRequest = { state.dismissRecurrenceDialog() },
            title = { Text("重复提醒") },
            text = {
                Column {
                    Text("到点提醒后是否自动重复？")
                    Spacer(Modifier.height(8.dp))
                    RECURRENCE_CHOICES.forEach { (value, label) ->
                        TextButton(
                            onClick = { state.applyRecurrence(value) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (value == state.recurrence) "✓ $label" else label,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { state.dismissRecurrenceDialog() }) { Text("取消") }
            }
        )
    }

    if (state.showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { state.dismissHistoryDialog() },
            title = { Text("历史版本") },
            text = {
                if (state.historyVersions.isEmpty()) {
                    Text("暂无历史版本：便签修改后会自动留存旧版本。")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(state.historyVersions, key = { it.historyId }) { version ->
                            TextButton(
                                onClick = { state.restoreVersion(version.historyId) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        android.text.format.DateFormat.format(
                                            "yyyy/MM/dd HH:mm", version.savedAt
                                        ).toString(),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        if (version.isLocked) {
                                            "加密版本（恢复后需解锁查看）"
                                        } else {
                                            version.title.ifBlank {
                                                stripImageTokens(version.content, "[图片]")
                                            }.lineSequence().firstOrNull().orEmpty().take(24)
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
            },
            confirmButton = {
                TextButton(onClick = { state.dismissHistoryDialog() }) { Text("关闭") }
            }
        )
    }

    if (showNotifDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showNotifDeniedDialog = false },
            title = { Text("需要通知权限") },
            text = { Text("没有通知权限时，提醒到点不会弹出通知。可在系统设置中开启本应用的通知权限。") },
            confirmButton = {
                TextButton(onClick = {
                    showNotifDeniedDialog = false
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = { showNotifDeniedDialog = false }) { Text("取消") }
            }
        )
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            title = "选择文件夹",
            folders = folderPaths,
            selected = state.folder,
            showAllOption = false,
            onSelect = { value ->
                state.onFolderSelected(value ?: "")
                showFolderPicker = false
            },
            onDismiss = { showFolderPicker = false },
            onCreate = { state.createFolder(it) },
            onRename = { old, new ->
                if (folderInSubtree(state.folder, old)) {
                    state.onFolderSelected(new + state.folder.substring(old.length))
                }
                state.renameFolder(old, new)
            },
            onDelete = { path ->
                if (folderInSubtree(state.folder, path)) {
                    val parent = folderParent(path)
                    val suffix = state.folder.substring(path.length)
                    state.onFolderSelected(if (parent.isEmpty()) suffix.trimStart('/') else parent + suffix)
                }
                state.deleteFolder(path)
            }
        )
    }

    removingImageBlockId?.let { blockId ->
        AlertDialog(
            onDismissRequest = { removingImageBlockId = null },
            title = { Text("移除图片？") },
            text = { Text("图片将从便签中删除并清理本地文件，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    state.removeImageBlock(blockId)
                    removingImageBlockId = null
                }) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removingImageBlockId = null }) { Text("取消") }
            }
        )
    }

    viewingImagePath?.let { path ->
        ImageViewerDialog(
            imagePath = path,
            onDismiss = { viewingImagePath = null }
        )
    }
}

@Composable
private fun LockedEditPlaceholder(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text("该便签已加密", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "请输入 PIN 解锁后查看和编辑",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onUnlock) { Text("解锁") }
    }
}

@Composable
private fun DecryptFailedPlaceholder(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Text("无法解密此便签", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "当前 PIN 无法解密这条便签：它可能来自使用其他 PIN 的设备，或 PIN 已被更改。" +
                "为避免覆盖数据，编辑与保存已停用。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("返回") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagInputRow(
    tags: MutableList<String>,
    onTagsChanged: () -> Unit
) {
    var newTag by remember { mutableStateOf("") }
    val addTag = {
        val t = newTag.trim().replace(",", "")
        if (t.isNotEmpty() && !tags.contains(t)) tags.add(t)
        newTag = ""
        onTagsChanged()
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    AssistChip(
                        // 点 chip 本体不再删除标签（误触即永久移除）；只有点 ✕ 才移除
                        onClick = {},
                        label = { Text("#$tag") },
                        trailingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable {
                                        tags.remove(tag)
                                        onTagsChanged()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除标签",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newTag,
                onValueChange = { newTag = it },
                placeholder = { Text("添加标签") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addTag() }),
                colors = transparentFieldColors()
            )
            IconButton(onClick = addTag) { Icon(Icons.Filled.Add, contentDescription = "添加标签") }
        }
    }
}

@Composable
private fun transparentFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.onSurface
)
