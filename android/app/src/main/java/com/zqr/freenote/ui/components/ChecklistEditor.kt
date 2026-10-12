package com.zqr.freenote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zqr.freenote.data.imageTokenPath
import com.zqr.freenote.data.isImageTokenLine
import com.zqr.freenote.data.resolveImageTokenPath
import java.io.File

data class ChecklistItem(val checked: Boolean, val text: String)

fun parseChecklist(content: String): List<ChecklistItem> {
    if (content.isBlank()) return listOf(ChecklistItem(false, ""))
    val items = content.split("\n").mapNotNull { line ->
        when {
            line.startsWith("- [ ]") -> ChecklistItem(false, line.drop(6))
            line.startsWith("- [x]") || line.startsWith("- [X]") ->
                ChecklistItem(true, line.drop(6))
            line.isBlank() -> null
            else -> ChecklistItem(false, line)
        }
    }
    return if (items.isEmpty()) listOf(ChecklistItem(false, "")) else items
}

fun serializeChecklist(items: List<ChecklistItem>): String =
    items.joinToString("\n") { (checked, text) ->
        // 图片标记必须原样独占一行：加了 "- [ ] " 前缀后 parseImageTokens 就认不出来，
        // 保存时的孤儿图片清理会把它引用的文件删掉
        if (isImageTokenLine(text)) text else "- [${if (checked) "x" else " "}] $text"
    }

/**
 * 反复剥离行首的标题、列表、引用标记（支持 "> - item" 这类叠加写法）。
 * 剥完后只剩标记符号的行（如 "#"、"- "）没有实际内容，返回空串。
 */
private fun stripLeadingMarkers(line: String): String {
    var rest = line.trim()
    while (true) {
        rest = when {
            rest.startsWith("### ") -> rest.substring(4)
            rest.startsWith("## ") -> rest.substring(3)
            rest.startsWith("# ") -> rest.substring(2)
            rest.startsWith("- ") || rest.startsWith("* ") || rest.startsWith("+ ") -> rest.substring(2)
            rest.startsWith(">") -> rest.trimStart('>', ' ')
            else -> break
        }.trim()
    }
    return if (rest.all { it in "#-*+>" }) "" else rest
}

/**
 * 正文 Markdown 转清单：剥离行首标记（#、-、*、+、>）与行内标记（**、*、`），
 * 丢弃空行，避免清单项里残留语法符号。已经是复选框的行保留其勾选状态；
 * 图片标记行原样保留，保证图片引用不被破坏。
 */
fun markdownToChecklist(content: String): String {
    val items = content.split("\n").mapNotNull { raw ->
        val line = raw.trim()
        val item = when {
            line.isEmpty() -> null
            isImageTokenLine(line) -> ChecklistItem(false, line)
            line.startsWith("- [ ]") -> ChecklistItem(false, markdownInlineToPlain(line.drop(6)))
            line.startsWith("- [x]") || line.startsWith("- [X]") ->
                ChecklistItem(true, markdownInlineToPlain(line.drop(6)))
            else -> ChecklistItem(false, markdownInlineToPlain(stripLeadingMarkers(line)))
        }
        item?.takeIf { it.text.isNotBlank() }
    }
    return if (items.isEmpty()) "" else serializeChecklist(items)
}

/** 切换清单是否会不可逆地改动正文（含 Markdown 标记、空行或行首/行尾空白）：用于转换前提示。 */
fun markdownToChecklistLosesMarkup(content: String): Boolean {
    if (content.isBlank()) return false
    return content.split("\n").any { raw ->
        val line = raw.trim()
        when {
            // 空行会被丢弃；行首缩进/行尾空格会被 trim 掉
            line.isEmpty() -> true
            raw != line -> true
            else -> MARKDOWN_MARKER_REGEX.containsMatchIn(line)
        }
    }
}

private val MARKDOWN_MARKER_REGEX = Regex("^(#{1,6}\\s|[-*+]\\s|>)|(\\*\\*|\\*|`|\\[\\[)")

@Composable
fun ChecklistEditor(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 删除某项后回调（previous=删除前正文，deleted=删除后正文）：
     * 供界面提供「撤销」，与自动保存落库赛跑，避免误触删除无法恢复。
     */
    onItemDeleted: ((previous: String, deleted: String) -> Unit)? = null
) {
    val items = remember(content) { parseChecklist(content) }
    // 图片标记行在清单里仍按图片呈现，而不是显示原始标记文本
    val imagePaths = remember(items) { items.map { imageTokenPath(it.text) } }
    var newItem by remember { mutableStateOf("") }

    // 用普通 Column 而非 LazyColumn：清单项很少，且外层已是 verticalScroll 容器，
    // 避免同轴嵌套滚动导致的布局异常
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            val imagePath = imagePaths.getOrNull(index)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                if (imagePath != null) {
                    val context = LocalContext.current
                    val resolved = remember(imagePath) { resolveImageTokenPath(context, imagePath) }
                    if (resolved != null) {
                        AsyncImage(
                            model = File(resolved),
                            contentDescription = "清单中的图片",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        Text(
                            "［图片不可用或待同步］",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Checkbox(
                        checked = item.checked,
                        onCheckedChange = { newValue ->
                            val updated = items.toMutableList()
                            updated[index] = item.copy(checked = newValue)
                            onContentChange(serializeChecklist(updated))
                        }
                    )
                    Text(
                        text = item.text,
                        textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f)
                    )
                }
                IconButton(onClick = {
                    val previous = content
                    val updated = items.toMutableList()
                    val next = if (updated.size == 1) {
                        // 删除唯一一项时直接清空内容，避免残留 "- [ ] " 占位
                        ""
                    } else {
                        updated.removeAt(index)
                        serializeChecklist(updated)
                    }
                    onContentChange(next)
                    onItemDeleted?.invoke(previous, next)
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "删除项")
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp)
        ) {
            OutlinedTextField(
                value = newItem,
                onValueChange = { newItem = it },
                placeholder = { Text("添加新项") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                if (newItem.isNotBlank()) {
                    val updated = items.toMutableList()
                    if (updated.isNotEmpty() && updated.last().text.isBlank() && !updated.last().checked) {
                        updated[updated.lastIndex] = ChecklistItem(false, newItem.trim())
                    } else {
                        updated.add(ChecklistItem(false, newItem.trim()))
                    }
                    onContentChange(serializeChecklist(updated))
                    newItem = ""
                }
            }) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
    }
}
