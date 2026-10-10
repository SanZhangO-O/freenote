package com.zqr.freenote.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zqr.freenote.data.normalizeFolder

/**
 * 文件夹选择/管理对话框（列表页筛选与编辑页归属共用）：
 * - 点击行选择（onSelect(null) 表示「全部便签」，仅在 showAllOption=true 时提供）；
 * - 行尾菜单：新建子文件夹 / 重命名 / 删除（删除时便签移至上级，绝不删除便签）；
 * - 底部「新建文件夹」支持多级路径（用 / 分隔）。
 */
@Composable
fun FolderPickerDialog(
    title: String,
    folders: List<String>,
    selected: String?,
    showAllOption: Boolean,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var menuFolder by remember { mutableStateOf<String?>(null) }
    var nameDialog by remember { mutableStateOf<NameDialogState?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                if (showAllOption) {
                    item {
                        FolderRow(
                            name = "全部便签",
                            indent = 0,
                            selected = selected == null,
                            onClick = { onSelect(null) }
                        )
                    }
                }
                item {
                    FolderRow(
                        name = "未分类",
                        indent = 0,
                        selected = selected == "",
                        onClick = { onSelect("") }
                    )
                }
                items(folders, key = { it }) { path ->
                    FolderRow(
                        name = path.substringAfterLast('/'),
                        indent = path.count { it == '/' },
                        selected = selected == path,
                        onClick = { onSelect(path) },
                        trailing = {
                            Box {
                                IconButton(onClick = { menuFolder = path }) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = "文件夹操作",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuFolder == path,
                                    onDismissRequest = { menuFolder = null }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("新建子文件夹") },
                                        onClick = {
                                            menuFolder = null
                                            nameDialog = NameDialogState("create", path)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("重命名") },
                                        onClick = {
                                            menuFolder = null
                                            nameDialog = NameDialogState("rename", path)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除") },
                                        onClick = {
                                            menuFolder = null
                                            deleteTarget = path
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { nameDialog = NameDialogState("create", "") }) {
                Text("新建文件夹")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    nameDialog?.let { dialog ->
        val creating = dialog.mode == "create"
        FolderNameDialog(
            title = if (creating) "新建文件夹" else "重命名文件夹",
            initial = if (creating) {
                if (dialog.base.isEmpty()) "" else "${dialog.base}/"
            } else {
                dialog.base
            },
            confirmLabel = if (creating) "创建" else "重命名",
            onConfirm = { value ->
                val folder = normalizeFolder(value)
                nameDialog = null
                if (folder.isNotEmpty()) {
                    if (creating) {
                        if (folder != dialog.base) onCreate(folder)
                    } else if (folder != dialog.base) {
                        onRename(dialog.base, folder)
                    }
                }
            },
            onDismiss = { nameDialog = null }
        )
    }

    deleteTarget?.let { path ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除文件夹") },
            text = {
                Text("删除文件夹「$path」？其下便签（含子文件夹）将移至上级，不会被删除。")
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    onDelete(path)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

private data class NameDialogState(val mode: String, val base: String)

@Composable
private fun FolderRow(
    name: String,
    indent: Int,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (indent * 16).dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选择",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun FolderNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text("用 / 分隔多级，如：工作/项目A") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
