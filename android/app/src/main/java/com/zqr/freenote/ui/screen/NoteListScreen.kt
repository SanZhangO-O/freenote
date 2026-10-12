package com.zqr.freenote.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zqr.freenote.data.Note
import com.zqr.freenote.data.NoteColor
import com.zqr.freenote.data.stripImageTokens
import com.zqr.freenote.data.sync.SyncManager
import com.zqr.freenote.ui.common.themedColor
import com.zqr.freenote.ui.components.FolderPickerDialog
import com.zqr.freenote.util.TimeFormat
import com.zqr.freenote.viewmodel.NoteViewModel
import com.zqr.freenote.viewmodel.SortMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteListScreen(
    viewModel: NoteViewModel,
    onNavigateToEdit: (String?) -> Unit,
    onNavigateToLock: (String) -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val notes by viewModel.visibleNotes.collectAsState()
    val tags by viewModel.allTags.collectAsState()
    val selectedTag by viewModel.selectedTag.collectAsState()
    val selectedFolder by viewModel.selectedFolder.collectAsState()
    val folderPaths by viewModel.folderPaths.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val sortDescending by viewModel.sortDescending.collectAsState()
    val timelineMode by viewModel.timelineMode.collectAsState()
    var showFolderPicker by remember { mutableStateOf(false) }
    var noteToDelete by remember { mutableStateOf<Note?>(null) }
    // 多选批量操作：长按卡片进入，顶栏切换到批量操作
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var batchTrashConfirm by remember { mutableStateOf(false) }
    var batchFolderPick by remember { mutableStateOf(false) }
    var batchTagDialog by remember { mutableStateOf(false) }
    var batchTagText by remember { mutableStateOf("") }

    fun toggleSelected(noteId: String) {
        val next = ArrayList(selectedIds)
        if (!next.remove(noteId)) next.add(noteId)
        selectedIds = next
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = arrayListOf()
    }

    // 多选模式下系统返回键：先退出多选而不是离开界面
    BackHandler(enabled = selectionMode) { exitSelection() }

    // 旋转等重建后与 ViewModel 中的查询状态对齐，避免搜索栏收起但筛选仍生效
    var showSearch by remember { mutableStateOf(searchQuery.isNotEmpty()) }
    // 搜索态系统返回键：先收起搜索（并清空查询），而不是直接离开列表页
    BackHandler(enabled = showSearch) {
        showSearch = false
        viewModel.clearSearch()
    }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val syncing by SyncManager.syncing.collectAsState()
    val appliedChanges by SyncManager.appliedChanges.collectAsState()
    LaunchedEffect(appliedChanges) {
        // 同步拉取到远端改动：重排提醒并刷新小组件
        if (appliedChanges > 0) viewModel.onRemoteSyncApplied()
    }
    LaunchedEffect(showSearch) {
        if (showSearch) searchFocusRequester.requestFocus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                TopAppBar(
                    title = {
                        if (selectionMode) {
                            Text("已选 ${selectedIds.size} 条")
                        } else if (showSearch) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.setSearchQuery(it) },
                                placeholder = { Text("搜索便签...") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester),
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.clearSearch() }) {
                                            Icon(Icons.Filled.Close, contentDescription = "清除", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            )
                        } else {
                            Text("FreeNote")
                        }
                    },
                    actions = {
                        if (selectionMode) {
                            IconButton(onClick = {
                                selectedIds = ArrayList(notes.map { it.id })
                            }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = "全选")
                            }
                            IconButton(onClick = { batchTrashConfirm = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = "移到回收站")
                            }
                            IconButton(onClick = { batchFolderPick = true }) {
                                Icon(Icons.Filled.DriveFileMove, contentDescription = "移动文件夹")
                            }
                            IconButton(onClick = {
                                batchTagText = ""
                                batchTagDialog = true
                            }) {
                                Icon(Icons.Filled.LocalOffer, contentDescription = "添加标签")
                            }
                            IconButton(onClick = { exitSelection() }) {
                                Icon(Icons.Filled.Close, contentDescription = "退出多选")
                            }
                        } else {
                            IconButton(
                                enabled = !syncing,
                                onClick = {
                                scope.launch { snackbarHostState.showSnackbar(SyncManager.syncNow()) }
                            }
                        ) {
                            if (syncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Filled.Sync, contentDescription = "立即同步")
                            }
                        }
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) viewModel.clearSearch()
                        }) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = { showFolderPicker = true }) {
                            Icon(Icons.Outlined.Folder, contentDescription = "文件夹")
                        }
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("按修改时间") },
                                    trailingIcon = {
                                        if (sortMode == SortMode.UPDATED) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        viewModel.setSortMode(SortMode.UPDATED)
                                        sortMenuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("按创建时间") },
                                    trailingIcon = {
                                        if (sortMode == SortMode.CREATED) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        viewModel.setSortMode(SortMode.CREATED)
                                        sortMenuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("按标题") },
                                    trailingIcon = {
                                        if (sortMode == SortMode.TITLE) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        viewModel.setSortMode(SortMode.TITLE)
                                        sortMenuExpanded = false
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("按日期分组（时间线）") },
                                    trailingIcon = {
                                        if (timelineMode) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        viewModel.setTimelineMode(!timelineMode)
                                        sortMenuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("升序") },
                                    trailingIcon = {
                                        if (!sortDescending) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        viewModel.setSortDescending(false)
                                        sortMenuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("降序（新→旧 / Z→A）") },
                                    trailingIcon = {
                                        if (sortDescending) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        viewModel.setSortDescending(true)
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                        }
                        IconButton(onClick = onNavigateToTrash) {
                            // 回收站用垃圾桶图标；DeleteSweep 的语义是「清空」，容易误解为清空回收站
                            Icon(Icons.Outlined.Delete, contentDescription = "回收站")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                selectedFolder?.let { folderFilter ->
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = true,
                            onClick = { showFolderPicker = true },
                            label = {
                                Text(if (folderFilter.isEmpty()) "未分类" else "📁 $folderFilter")
                            },
                            trailingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable { viewModel.setSelectedFolder(null) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "清除文件夹筛选",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        )
                    }
                }
                if (tags.isNotEmpty()) {
                    val allTagNames = remember(tags) {
                        tags.flatMap { it.split(",").map(String::trim).filter(String::isNotEmpty) }.distinct()
                    }
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedTag == null,
                            onClick = { viewModel.setSelectedTag(null) },
                            label = { Text("全部") }
                        )
                        allTagNames.forEach { tag ->
                            FilterChip(
                                selected = selectedTag == tag,
                                onClick = { viewModel.toggleTag(tag) },
                                label = { Text(tag) }
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(
                    onClick = { onNavigateToEdit(null) },
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新建便签")
                }
            }
        }
    ) { paddingValues ->
        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val emptyTitle = when {
                        searchQuery.isNotEmpty() -> "没有匹配的便签"
                        selectedTag != null -> "该标签下没有便签"
                        selectedFolder != null -> "该文件夹下没有便签"
                        else -> "还没有便签"
                    }
                    val emptyHint = when {
                        searchQuery.isNotEmpty() -> "换个关键词试试"
                        selectedTag != null -> "新建带该标签的便签，或切换到其他标签"
                        selectedFolder != null -> "把便签移动到该文件夹，或切换到其他文件夹"
                        else -> "点击 + 创建第一个便签"
                    }
                    Text(
                        text = emptyTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else if (timelineMode) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp,
                    top = paddingValues.calculateTopPadding() + 8.dp,
                    bottom = paddingValues.calculateBottomPadding() + 80.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                var lastLabel: String? = null
                notes.forEach { note ->
                    val label = dayGroupLabel(note.updatedAt)
                    if (label != lastLabel) {
                        item(key = "day-$label") {
                            Text(
                                label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
                            )
                        }
                        lastLabel = label
                    }
                    item(key = note.id) {
                        SwipeToDismissNoteCard(
                            note = note,
                            selected = selectionMode && note.id in selectedIds,
                            selectionMode = selectionMode,
                            onLongClick = {
                                if (!selectionMode) selectionMode = true
                                if (note.id !in selectedIds) toggleSelected(note.id)
                            },
                            onClick = {
                                if (selectionMode) {
                                    toggleSelected(note.id)
                                } else if (note.isLocked) {
                                    onNavigateToLock(note.id)
                                } else {
                                    onNavigateToEdit(note.id)
                                }
                            },
                            onPin = { viewModel.togglePinned(note) },
                            onDelete = { noteToDelete = note }
                        )
                    }
                }
            }
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp,
                    top = paddingValues.calculateTopPadding() + 8.dp,
                    bottom = paddingValues.calculateBottomPadding() + 80.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp,
                modifier = Modifier.fillMaxSize()
            ) {
                items(notes, key = { it.id }) { note ->
                    SwipeToDismissNoteCard(
                        note = note,
                        selected = selectionMode && note.id in selectedIds,
                        selectionMode = selectionMode,
                        onLongClick = {
                            if (!selectionMode) selectionMode = true
                            if (note.id !in selectedIds) toggleSelected(note.id)
                        },
                        onClick = {
                            if (selectionMode) {
                                toggleSelected(note.id)
                            } else if (note.isLocked) {
                                onNavigateToLock(note.id)
                            } else {
                                onNavigateToEdit(note.id)
                            }
                        },
                        onPin = { viewModel.togglePinned(note) },
                        onDelete = { noteToDelete = note }
                    )
                }
            }
        }
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            title = "文件夹",
            folders = folderPaths,
            selected = selectedFolder,
            showAllOption = true,
            onSelect = { value ->
                viewModel.setSelectedFolder(value)
                showFolderPicker = false
            },
            onDismiss = { showFolderPicker = false },
            onCreate = { viewModel.createFolder(it) },
            onRename = { old, new -> viewModel.renameFolder(old, new) },
            onDelete = { path -> viewModel.deleteFolder(path) }
        )
    }

    noteToDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text("删除便签") },
            text = { Text("便签将移入回收站，30 天后自动清除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.moveToTrash(note)
                    noteToDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (batchTrashConfirm) {
        AlertDialog(
            onDismissRequest = { batchTrashConfirm = false },
            title = { Text("删除便签") },
            text = { Text("将选中的 ${selectedIds.size} 条便签移到回收站？30 天后自动清除。") },
            confirmButton = {
                TextButton(onClick = {
                    batchTrashConfirm = false
                    viewModel.moveToTrashBatch(notes.filter { it.id in selectedIds })
                    exitSelection()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { batchTrashConfirm = false }) { Text("取消") }
            }
        )
    }

    if (batchFolderPick) {
        FolderPickerDialog(
            title = "移动到文件夹",
            folders = folderPaths,
            selected = null,
            showAllOption = false,
            onSelect = { folder ->
                batchFolderPick = false
                viewModel.moveToFolderBatch(notes.filter { it.id in selectedIds }, folder ?: "")
                exitSelection()
            },
            onDismiss = { batchFolderPick = false },
            onCreate = { viewModel.createFolder(it) },
            onRename = { old, new -> viewModel.renameFolder(old, new) },
            onDelete = { path -> viewModel.deleteFolder(path) }
        )
    }

    if (batchTagDialog) {
        AlertDialog(
            onDismissRequest = { batchTagDialog = false },
            title = { Text("添加标签") },
            text = {
                Column {
                    Text("标签会添加到选中的 ${selectedIds.size} 条便签（多个用逗号或空格分隔）")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = batchTagText,
                        onValueChange = { batchTagText = it },
                        label = { Text("标签") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = batchTagText.isNotBlank(),
                    onClick = {
                        batchTagDialog = false
                        val newTags = batchTagText
                            .split(',', '，', ' ', '　')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        viewModel.addTagsBatch(notes.filter { it.id in selectedIds }, newTags)
                        exitSelection()
                    }
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { batchTagDialog = false }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SwipeToDismissNoteCard(
    note: Note,
    selected: Boolean,
    selectionMode: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                // 不直接滑掉卡片，交由删除确认对话框处理
                onDelete()
            }
            value == SwipeToDismissBoxValue.Settled
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !selectionMode,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "滑动删除",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = 24.dp)
                )
            }
        }
    ) {
        NoteCard(
            note = note,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
            onPin = onPin,
            onDelete = onDelete
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: Note,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    val noteColor = NoteColor.themedColor(note.color, MaterialTheme.colorScheme)
    val animatedColor by animateColorAsState(targetValue = noteColor, label = "noteColor")
    val tagList = remember(note.tags) { note.tagList() }
    // 摘要去掉图片标记需遍历全文：缓存结果，避免滚动时每次重组都重算
    val summary = remember(note.content) { stripImageTokens(note.content, "[图片]") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = animatedColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (note.isPinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "已置顶",
                            modifier = Modifier
                                .size(14.dp)
                                .padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (note.isLocked) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = "已锁定",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (note.reminderTime != null) {
                        Icon(
                            Icons.Outlined.Notifications,
                            contentDescription = "有提醒",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (note.title.isNotEmpty()) {
                        Text(
                            text = if (note.isLocked) "已加密便签" else note.title,
                            style = MaterialTheme.typography.titleSmall,
                            // 锁定便签标题是固定占位文案，单行显示即可，避免被挤成两行断字
                            maxLines = if (note.isLocked) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row {
                    // 触控目标 40dp 且两个按钮间留 8dp：零间距的置顶/删除极易互相误触
                    IconButton(onClick = onPin, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (note.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "置顶",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            if (!note.isLocked && note.content.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
            if (tagList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tagList.take(3).forEach { tag ->
                        Text(
                            text = "#$tag",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            if (note.folder.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "📁 ${note.folder}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = TimeFormat.noteTime(note.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
    }
}

/** 时间线分组的日期标签：今天 / 昨天 / M月d日（跨年带年份）。 */
private fun dayGroupLabel(timeMs: Long): String {
    val now = java.util.Calendar.getInstance()
    val day = java.util.Calendar.getInstance().apply { timeInMillis = timeMs }
    val sameYear = now.get(java.util.Calendar.YEAR) == day.get(java.util.Calendar.YEAR)
    if (sameYear &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == day.get(java.util.Calendar.DAY_OF_YEAR)
    ) {
        return "今天"
    }
    val yesterday = (now.clone() as java.util.Calendar).apply {
        add(java.util.Calendar.DAY_OF_YEAR, -1)
    }
    if (sameYear &&
        yesterday.get(java.util.Calendar.DAY_OF_YEAR) == day.get(java.util.Calendar.DAY_OF_YEAR)
    ) {
        return "昨天"
    }
    val month = day.get(java.util.Calendar.MONTH) + 1
    val dayOfMonth = day.get(java.util.Calendar.DAY_OF_MONTH)
    return if (sameYear) {
        "${month}月${dayOfMonth}日"
    } else {
        "${day.get(java.util.Calendar.YEAR)}年${month}月${dayOfMonth}日"
    }
}
