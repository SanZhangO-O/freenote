package com.zqr.freenote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File

/**
 * Top bar for the note editor: back navigation, pin toggle, overflow menu
 * (checklist / reminder / lock / preview) and save action.
 *
 * All mutating decisions are delegated to callbacks so the owning screen
 * stays the single source of truth for state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditTopBar(
    screenTitle: String,
    animatedColor: Color,
    isPinned: Boolean,
    menuExpanded: Boolean,
    isChecklist: Boolean,
    reminderTime: Long?,
    isLocked: Boolean,
    previewMode: Boolean,
    interactive: Boolean = true,
    onBack: () -> Unit,
    onTogglePin: () -> Unit,
    onMenuExpandedChange: (Boolean) -> Unit,
    onToggleChecklist: () -> Unit,
    onPickReminder: () -> Unit,
    onClearReminder: () -> Unit,
    onLockToggled: () -> Unit,
    onTogglePreview: () -> Unit,
    onShareText: () -> Unit,
    onExportMarkdown: () -> Unit,
    onShowHistory: () -> Unit,
    onInsertTemplate: () -> Unit,
    onSave: () -> Unit
) {
    TopAppBar(
        title = { Text(screenTitle) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            if (interactive) {
                IconButton(onClick = onTogglePin) {
                    Icon(
                        if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = "置顶"
                    )
                }
                IconButton(onClick = onLockToggled) {
                    Icon(
                        if (isLocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                        contentDescription = if (isLocked) "解除锁定" else "锁定便签"
                    )
                }
                IconButton(onClick = { onMenuExpandedChange(true) }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isChecklist) "切换为文本" else "切换为清单") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = null) },
                        onClick = {
                            onToggleChecklist()
                            onMenuExpandedChange(false)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (reminderTime == null) "设置提醒" else "修改提醒") },
                        leadingIcon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                        onClick = {
                            onPickReminder()
                            onMenuExpandedChange(false)
                        }
                    )
                    if (reminderTime != null) {
                        DropdownMenuItem(
                            text = { Text("清除提醒") },
                            onClick = {
                                onClearReminder()
                                onMenuExpandedChange(false)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (previewMode) "编辑模式" else "预览 (Markdown)") },
                        onClick = {
                            onTogglePreview()
                            onMenuExpandedChange(false)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("分享文本…") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = {
                            onShareText()
                            onMenuExpandedChange(false)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("导出为 Markdown…") },
                        leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                        onClick = {
                            onExportMarkdown()
                            onMenuExpandedChange(false)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("插入模板…") },
                        leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                        onClick = {
                            onInsertTemplate()
                            onMenuExpandedChange(false)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("历史版本…") },
                        leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                        onClick = {
                            onShowHistory()
                            onMenuExpandedChange(false)
                        }
                    )
                }
                IconButton(onClick = onSave) {
                    Icon(Icons.Filled.Check, contentDescription = "保存")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = animatedColor),
        modifier = Modifier.statusBarsPadding()
    )
}

/** Inline row showing the active reminder time with a clear affordance. */
@Composable
fun NoteEditReminderRow(
    formattedTime: String,
    onClear: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("提醒：$formattedTime", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            "清除",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onClear() }
        )
    }
}

/** Small "已加密" indicator shown while a note is locked. */
@Composable
fun NoteEditLockedIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("已加密（文本内容加密，图片不加密）", style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 内联图片编辑器：文字块与图片块按正文顺序交错排列。
 * 图片作为正文的一部分插入（独占一行的标记），不再是独立的附件列表。
 *
 * 交互：
 * - 点按图片打开全屏查看器；
 * - 长按图片拖动，与相邻块交换位置，把图片调整到正文任意位置；
 * - [focusBlockId] 指定的文字块会被程序化聚焦并唤起输入法（插入图片后继续输入）。
 */
@Composable
fun InlineNoteEditor(
    blocks: List<EditorBlock>,
    focusBlockId: String?,
    onTextChange: (String, TextFieldValue) -> Unit,
    onFocusBlock: (String) -> Unit,
    onFocusConsumed: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onMoveBlock: (String, Int) -> Unit,
    onReorderFinished: () -> Unit,
    onBackspaceAtStart: (String) -> Boolean,
    modifier: Modifier = Modifier
) {
    // 拖拽回调里拿到的是组合时的闭包；用 rememberUpdatedState 保证读到最新的块列表
    val currentBlocks by rememberUpdatedState(blocks)
    var draggingBlockId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    // 各块中心点（根坐标）：拖拽时用于判断是否越过相邻块
    val itemCenters = remember { mutableMapOf<String, Float>() }

    Column(modifier = modifier) {
        blocks.forEach { block ->
            key(block.id) {
                val isDragging = draggingBlockId == block.id
                // 文字块长按留给文本选择，只有图片块响应长按拖动
                val dragModifier = if (block is EditorImageBlock) {
                    Modifier.pointerInput(block.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingBlockId = block.id
                                dragOffsetY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetY += dragAmount.y
                                val from = currentBlocks.indexOfFirst { it.id == block.id }
                                val layoutCenter = itemCenters[block.id]
                                if (from < 0 || layoutCenter == null) {
                                    return@detectDragGesturesAfterLongPress
                                }
                                val draggedCenter = layoutCenter + dragOffsetY
                                val neighbor = currentBlocks.getOrNull(
                                    if (dragOffsetY > 0) from + 1 else from - 1
                                )
                                val neighborCenter = neighbor?.let { itemCenters[it.id] }
                                if (neighbor == null || neighborCenter == null) {
                                    return@detectDragGesturesAfterLongPress
                                }
                                val crossed = if (dragOffsetY > 0) {
                                    draggedCenter > neighborCenter
                                } else {
                                    draggedCenter < neighborCenter
                                }
                                if (crossed) {
                                    onMoveBlock(
                                        block.id,
                                        if (dragOffsetY > 0) from + 1 else from - 1
                                    )
                                    // 交换后拖动块的布局位置移到原邻居处，
                                    // 修正偏移量保持视觉位置跟手
                                    dragOffsetY += layoutCenter - neighborCenter
                                }
                            },
                            onDragEnd = {
                                draggingBlockId = null
                                dragOffsetY = 0f
                                onReorderFinished()
                            },
                            onDragCancel = {
                                draggingBlockId = null
                                dragOffsetY = 0f
                            }
                        )
                    }
                } else {
                    Modifier
                }

                Box(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            itemCenters[block.id] =
                                coords.positionInRoot().y + coords.size.height / 2f
                        }
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = dragOffsetY
                                scaleX = 1.02f
                                scaleY = 1.02f
                                shadowElevation = 12f
                            }
                        }
                        .then(dragModifier)
                ) {
                    when (block) {
                        is EditorTextBlock -> EditorTextItem(
                            block = block,
                            isOnlyBlock = blocks.size == 1,
                            focusBlockId = focusBlockId,
                            onFocusConsumed = onFocusConsumed,
                            onTextChange = onTextChange,
                            onFocusBlock = onFocusBlock,
                            onBackspaceAtStart = onBackspaceAtStart
                        )
                        is EditorImageBlock -> EditorImageItem(
                            block = block,
                            isDragging = isDragging,
                            onOpenImage = { onOpenImage(block.path) },
                            onRemoveImage = { onRemoveImage(block.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorTextItem(
    block: EditorTextBlock,
    isOnlyBlock: Boolean,
    focusBlockId: String?,
    onFocusConsumed: () -> Unit,
    onTextChange: (String, TextFieldValue) -> Unit,
    onFocusBlock: (String) -> Unit,
    onBackspaceAtStart: (String) -> Boolean
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 插入图片后把焦点落到图片后的文字块并唤起输入法，光标就位即可继续输入
    LaunchedEffect(focusBlockId) {
        if (focusBlockId != null && focusBlockId == block.id) {
            // 节点可能在效果执行前被重组移除，requestFocus 会抛异常，兜底忽略
            runCatching { focusRequester.requestFocus() }
            // 等窗口焦点与布局就绪后再唤起输入法（从图片选择器返回时
            // 窗口焦点可能尚未恢复，过早 show 会被系统忽略）
            withFrameNanos { }
            keyboard?.show()
            onFocusConsumed()
        }
    }

    OutlinedTextField(
        value = block.value,
        onValueChange = { onTextChange(block.id, it) },
        placeholder = {
            if (isOnlyBlock) {
                Text("写点什么...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (isOnlyBlock) 220.dp else 0.dp)
            // 块首退格交由块级合并处理（拼回上一块/删除上方图片），
            // 其余按键原样放行给文本框；块边界本不是字符，文本框自身删不动
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown &&
                    event.key == Key.Backspace &&
                    block.value.selection.collapsed &&
                    block.value.selection.start == 0 &&
                    onBackspaceAtStart(block.id)
            }
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocusBlock(block.id) },
        textStyle = TextStyle(fontSize = 16.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
private fun EditorImageItem(
    block: EditorImageBlock,
    isDragging: Boolean,
    onOpenImage: () -> Unit,
    onRemoveImage: () -> Unit
) {
    val exists = remember(block.path) { File(block.path).isFile }
    Box(modifier = Modifier.padding(vertical = 4.dp)) {
        if (exists) {
            AsyncImage(
                model = File(block.path),
                contentDescription = "插入的图片",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !isDragging) { onOpenImage() }
            )
        } else {
            Text(
                "［图片待同步或不可用］",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp)
            )
        }
        IconButton(
            onClick = onRemoveImage,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "移除图片",
                tint = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(2.dp)
                    .size(14.dp)
            )
        }
    }
}

/**
 * 全屏图片查看器：捏合缩放、缩放后拖动平移；
 * 未缩放时点按任意位置或右上角按钮退出。
 */
@Composable
fun ImageViewerDialog(
    imagePath: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (scale <= 1f) onDismiss()
                }
        ) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = "查看图片",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        }
                    }
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(2.dp)
                        .size(20.dp)
                )
            }
        }
    }
}

/**
 * PIN 输入对话框：用于锁定便签或会话 PIN 过期后的重新验证。
 * Input filtering and verification are delegated to the caller via
 * [onPinInputChange] / [onConfirm].
 */
@Composable
fun NoteEditLockPinDialog(
    pinInput: String,
    pinError: Boolean,
    title: String = "输入 PIN 以锁定便签",
    onPinInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = onPinInputChange,
                    label = { Text("PIN") },
                    singleLine = true,
                    isError = pinError,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    )
                )
                if (pinError) {
                    Text(
                        "PIN 错误",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
