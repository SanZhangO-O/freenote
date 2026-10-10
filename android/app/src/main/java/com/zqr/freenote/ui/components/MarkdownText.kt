package com.zqr.freenote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zqr.freenote.data.imageTokenPath
import com.zqr.freenote.data.resolveImageTokenPath
import java.io.File

private enum class LineKind { CHECKLIST, H1, H2, H3, BULLET, QUOTE, IMAGE, BLANK, PLAIN }

/** 预解析的行：正则工作只做一次并缓存，重组时直接复用 AnnotatedString。 */
private data class RenderedLine(
    val kind: LineKind,
    val checked: Boolean,
    val body: AnnotatedString,
    val imagePath: String? = null
)

/** 渲染块：普通行或 Markdown 表格（| a | b |）。 */
private sealed interface MdBlock

private data class MdLine(val line: RenderedLine) : MdBlock

private data class MdTable(val rows: List<List<AnnotatedString>>) : MdBlock

/** [[双向链接]] 注解标签：点击后由调用方按标题跳转。 */
private const val WIKI_TAG = "WIKI"

/**
 * 轻量 Markdown 渲染：标题(#~###)、粗体**、斜体*、行内代码`、列表、复选框 - [ ] / - [x]、
 * 引用 >、独占一行的内联图片 ![alt](路径)、管道表格 | a | b |、
 * [[便签标题]] 双向链接（提供 [onWikiLink] 时可点击跳转）。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    onWikiLink: ((String) -> Unit)? = null
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(text, linkColor) { buildBlocks(text, linkColor) }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdTable -> TableView(block.rows)
                is MdLine -> {
                    val rendered = block.line
                    when (rendered.kind) {
                        LineKind.CHECKLIST -> {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(
                                    if (rendered.checked) "☑" else "☐",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                BodyText(
                                    body = rendered.body,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 4.dp),
                                    onWikiLink = onWikiLink
                                )
                            }
                        }
                        LineKind.H1 -> BodyText(
                            body = rendered.body,
                            style = MaterialTheme.typography.titleLarge
                                .merge(SpanStyle(fontWeight = FontWeight.Bold)),
                            onWikiLink = onWikiLink
                        )
                        LineKind.H2 -> BodyText(
                            body = rendered.body,
                            style = MaterialTheme.typography.titleMedium
                                .merge(SpanStyle(fontWeight = FontWeight.Bold)),
                            onWikiLink = onWikiLink
                        )
                        LineKind.H3 -> BodyText(
                            body = rendered.body,
                            style = MaterialTheme.typography.titleMedium,
                            onWikiLink = onWikiLink
                        )
                        LineKind.BULLET -> BodyText(
                            body = rendered.body,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp),
                            onWikiLink = onWikiLink
                        )
                        LineKind.QUOTE -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .padding(vertical = 2.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                            )
                            BodyText(
                                body = rendered.body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                modifier = Modifier.padding(start = 8.dp),
                                onWikiLink = onWikiLink
                            )
                        }
                        LineKind.IMAGE -> {
                            val context = LocalContext.current
                            val resolved = remember(rendered.imagePath) {
                                rendered.imagePath?.let { resolveImageTokenPath(context, it) }
                            }
                            if (resolved != null) {
                                AsyncImage(
                                    model = File(resolved),
                                    contentDescription = "插入的图片",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 320.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Text(
                                    "［图片不可用或待同步］",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                        LineKind.BLANK -> Text(rendered.body)
                        LineKind.PLAIN -> BodyText(
                            body = rendered.body,
                            style = MaterialTheme.typography.bodyMedium,
                            onWikiLink = onWikiLink
                        )
                    }
                }
            }
        }
    }
}

/** 文本行：含 [[链接]] 且提供回调时用 ClickableText 实现按需点击跳转。 */
@Suppress("DEPRECATION")
@Composable
private fun BodyText(
    body: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onWikiLink: ((String) -> Unit)? = null
) {
    val annotations = remember(body, onWikiLink) {
        if (onWikiLink == null) emptyList() else body.getStringAnnotations(WIKI_TAG, 0, body.length)
    }
    if (annotations.isEmpty()) {
        Text(body, style = style, color = color, modifier = modifier)
    } else {
        val resolvedStyle = if (color == Color.Unspecified) style else style.copy(color = color)
        ClickableText(text = body, style = resolvedStyle, modifier = modifier) { offset ->
            annotations.firstOrNull { offset >= it.start && offset < it.end }
                ?.let { onWikiLink?.invoke(it.item) }
        }
    }
}

/** 管道表格：等宽列平铺，首行加粗 + 行间分隔线。 */
@Composable
private fun TableView(rows: List<List<AnnotatedString>>) {
    if (rows.isEmpty()) return
    val columns = rows.maxOf { it.size }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                for (column in 0 until columns) {
                    val cell = row.getOrNull(column) ?: AnnotatedString("")
                    Text(
                        text = cell,
                        style = MaterialTheme.typography.bodySmall.merge(
                            if (rowIndex == 0) SpanStyle(fontWeight = FontWeight.Bold)
                            else SpanStyle()
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
            if (rowIndex == 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private val inlineRegex = Regex("""(\*\*[^*]+\*\*|\*[^*]+\*|`[^`]+`|\[\[[^\[\]\n]+\]\])""")

/**
 * 行内标记 token 的纯文本内容：`**x**` → x，`*x*`/`` `x` `` → x，`[[x]]` → x。
 * 无法识别的 token 原样返回，避免新增语法时把内容截断。
 */
private fun inlineTokenText(token: String): String = when {
    token.startsWith("**") && token.endsWith("**") && token.length >= 4 ->
        token.substring(2, token.length - 2)
    token.startsWith("[[") && token.endsWith("]]") && token.length >= 4 ->
        token.substring(2, token.length - 2)
    token.length >= 2 && token.first() == token.last() && (token.startsWith("*") || token.startsWith("`")) ->
        token.substring(1, token.length - 1)
    else -> token
}

/**
 * 把含行内标记的文本还原为纯文本（保留标记内的文字）。
 * 与 [parseInline] 共用 [inlineRegex] 和 [inlineTokenText]，
 * 保证「渲染」与「转纯文本」两处语法不会各写一份而漂移。
 */
internal fun markdownInlineToPlain(text: String): String =
    inlineRegex.replace(text) { inlineTokenText(it.value) }.trim()

private fun parseInline(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var lastIndex = 0
    for (match in inlineRegex.findAll(text)) {
        append(text.substring(lastIndex, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(inlineTokenText(token))
            }
            token.startsWith("`") -> withStyle(
                SpanStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 14.sp
                )
            ) { append(inlineTokenText(token)) }
            token.startsWith("[[") -> {
                val start = length
                withStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                ) { append(inlineTokenText(token)) }
                addStringAnnotation(WIKI_TAG, inlineTokenText(token), start, length)
            }
            token.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(inlineTokenText(token))
            }
            else -> append(token)
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) append(text.substring(lastIndex))
}

/** 解析全文为渲染块；连续 `| ... |` 行且第二行是分隔行（|---|）时识别为表格。 */
private fun buildBlocks(text: String, linkColor: Color): List<MdBlock> {
    val lines = text.split("\n")
    val blocks = ArrayList<MdBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.trim().startsWith("|") &&
            index + 1 < lines.size &&
            isTableSeparator(lines[index + 1])
        ) {
            val rows = ArrayList<List<AnnotatedString>>()
            rows.add(splitTableRow(line).map { parseInline(it, linkColor) })
            index += 2
            while (index < lines.size && lines[index].trim().startsWith("|")) {
                rows.add(splitTableRow(lines[index]).map { parseInline(it, linkColor) })
                index++
            }
            blocks.add(MdTable(rows))
            continue
        }
        blocks.add(MdLine(renderLine(line, linkColor)))
        index++
    }
    return blocks
}

private fun renderLine(line: String, linkColor: Color): RenderedLine = when {
    line.startsWith("- [ ]") || line.startsWith("- [x]") || line.startsWith("- [X]") ->
        RenderedLine(
            LineKind.CHECKLIST,
            line.startsWith("- [x]", true),
            parseInline(line.drop(6), linkColor)
        )
    line.startsWith("### ") -> RenderedLine(LineKind.H3, false, parseInline(line.substring(4), linkColor))
    line.startsWith("## ") -> RenderedLine(LineKind.H2, false, parseInline(line.substring(3), linkColor))
    line.startsWith("# ") -> RenderedLine(LineKind.H1, false, parseInline(line.substring(2), linkColor))
    line.startsWith("- ") || line.startsWith("* ") ->
        RenderedLine(LineKind.BULLET, false, parseInline("• ${line.substring(2)}", linkColor))
    line.startsWith(">") ->
        RenderedLine(LineKind.QUOTE, false, parseInline(line.trimStart('>', ' '), linkColor))
    imageTokenPath(line) != null ->
        RenderedLine(LineKind.IMAGE, false, AnnotatedString(" "), imageTokenPath(line))
    line.isBlank() -> RenderedLine(LineKind.BLANK, false, AnnotatedString(" "))
    else -> RenderedLine(LineKind.PLAIN, false, parseInline(line, linkColor))
}

private val tableSeparatorCellRegex = Regex("^:?-{2,}:?$")

private fun isTableSeparator(line: String): Boolean {
    val cells = splitTableRow(line)
    return cells.isNotEmpty() && cells.all { tableSeparatorCellRegex.matches(it) }
}

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
