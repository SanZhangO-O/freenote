package com.zqr.freenote.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.util.UUID

/**
 * 编辑块模型与纯函数操作（无 Compose 状态、无 Android 依赖），
 * 供 ViewModel、编辑器界面与单元测试共用。
 */

sealed interface EditorBlock {
    val id: String
}

data class EditorTextBlock(
    override val id: String,
    val value: TextFieldValue,
    /**
     * 本块由插入图片在光标处切开而产生（上方紧邻图片/前半段，两者之间没有换行）。
     * 删除图片把两半拼回时按此标记决定分隔符：切开的拼回不加换行，
     * 原本就是两行的内容（如从 Windows 端同步来的标记行两侧）拼回时保留换行。
     */
    val splitFromPrevious: Boolean = false
) : EditorBlock

data class EditorImageBlock(
    override val id: String,
    /** 本地绝对路径（不含 file:// 前缀）。 */
    val path: String
) : EditorBlock

fun newEditorBlockId(): String = UUID.randomUUID().toString().take(8)

/**
 * 在焦点文字块 [focusBlockId] 的光标处插入图片块：
 * - 光标前的文字留在原处，光标后的文字移入图片之后的新文字块（图片真正插入正文中间）；
 * - 光标前的文字为空时不产生空块；图片之后总是补一个文字块（可为空），
 *   作为紧接图片继续输入的落点；
 * - 没有焦点块（或焦点块不是文字块）时图片追加到末尾，且不抢占焦点；
 *   末尾若恰好是空白文字块（如新建便签），图片插到它前面，输入区保持在图片下方。
 *
 * 返回新块列表，以及插入后应程序化聚焦的文字块 id（无则为 null）。
 */
fun insertImageBlocks(
    blocks: List<EditorBlock>,
    focusBlockId: String?,
    imagePaths: List<String>
): Pair<List<EditorBlock>, String?> {
    if (imagePaths.isEmpty()) return blocks to null
    val anchorIndex = blocks.indexOfFirst { it.id == focusBlockId }
    when (val anchor = blocks.getOrNull(anchorIndex)) {
        is EditorTextBlock -> {
            val cursor = anchor.value.selection.end.coerceIn(0, anchor.value.text.length)
            val before = anchor.value.text.substring(0, cursor)
            val after = anchor.value.text.substring(cursor)
            var continueBlockId: String? = null
            val result = buildList {
                addAll(blocks.take(anchorIndex))
                if (before.isNotEmpty()) {
                    add(
                        EditorTextBlock(
                            newEditorBlockId(),
                            TextFieldValue(before, TextRange(before.length)),
                            // 前半段继承锚点块的标记：与更上方图片的“无换行”关系不变
                            anchor.splitFromPrevious
                        )
                    )
                }
                imagePaths.forEach { add(EditorImageBlock(newEditorBlockId(), it)) }
                val continueId = newEditorBlockId()
                continueBlockId = continueId
                // 后半段是被这次插入切开的：与图片之间没有换行
                add(EditorTextBlock(continueId, TextFieldValue(after), splitFromPrevious = true))
                addAll(blocks.drop(anchorIndex + 1))
            }
            return result to continueBlockId
        }
        else -> {
            val images = imagePaths.map { EditorImageBlock(newEditorBlockId(), it) }
            val last = blocks.lastOrNull()
            val appended = if (last is EditorTextBlock && last.value.text.isBlank()) {
                blocks.dropLast(1) + images + last
            } else {
                blocks + images
            }
            return appended to null
        }
    }
}

/**
 * 把 [id] 对应的块移动到 [toIndex]（拖拽排序）。
 * id 不存在、目标越界或位置未变化时返回 null，调用方保持原顺序。
 */
fun moveBlockInList(blocks: List<EditorBlock>, id: String, toIndex: Int): List<EditorBlock>? {
    val from = blocks.indexOfFirst { it.id == id }
    if (from < 0 || toIndex !in blocks.indices || from == toIndex) return null
    val mutable = blocks.toMutableList()
    val block = mutable.removeAt(from)
    mutable.add(toIndex, block)
    return mutable
}

/**
 * 移除 [imageId] 对应的图片块并清理残留：
 * - 图片两侧都是文字块时拼回一个块（插入图片时正文在光标处被切开、不引入换行，
 *   删除图片后残留的换行必须随之消失）；
 * - 拼接后丢弃空白文字块（与序列化规则一致，避免留下空白输入区）；
 * - 列表为空时补一个空文字块。
 *
 * 返回新块列表，以及拼接块 id（供需要转移焦点的调用方使用；无可聚焦块时为 null）。
 */
fun removeImageAndMerge(blocks: List<EditorBlock>, imageId: String): Pair<List<EditorBlock>, String?> {
    val index = blocks.indexOfFirst { it.id == imageId }
    if (index < 0 || blocks[index] !is EditorImageBlock) return blocks to null
    val mutable = blocks.toMutableList()
    mutable.removeAt(index)

    var mergedInto: String? = null
    val previous = mutable.getOrNull(index - 1)
    val following = mutable.getOrNull(index)
    if (previous is EditorTextBlock && following is EditorTextBlock) {
        val separator = if (following.splitFromPrevious) "" else "\n"
        val joined = previous.value.text + separator + following.value.text
        mutable[index - 1] = EditorTextBlock(
            previous.id,
            TextFieldValue(joined, TextRange(previous.value.text.length + separator.length)),
            previous.splitFromPrevious
        )
        mutable.removeAt(index)
        mergedInto = previous.id
    }

    val cleaned = mutable.filterNot { it is EditorTextBlock && it.value.text.isBlank() }
        .ifEmpty { listOf(EditorTextBlock(newEditorBlockId(), TextFieldValue(""))) }
    // 拼接块若为空白已被一并清掉，此时无可聚焦块
    val focusId = mergedInto?.takeIf { id -> cleaned.any { it.id == id } }
    return cleaned to focusId
}

/**
 * 光标在文字块开头按退格时的块级处理（块边界处的删除）：
 * - 前一块是文字块 → 拼进前一块，光标落在拼接处；
 * - 前一块是图片块 → 删除该图片并把两侧文字块按 [removeImageAndMerge] 的规则拼回；
 * - 没有前一块（或 id 不存在）返回 null，调用方交给文本框自行处理。
 *
 * 返回新块列表，以及应聚焦的文字块 id（当前块未被移除时为 null，焦点保持不变）。
 */
fun mergeBackwardAtStart(
    blocks: List<EditorBlock>,
    textBlockId: String
): Pair<List<EditorBlock>, String?>? {
    val index = blocks.indexOfFirst { it.id == textBlockId }
    val current = blocks.getOrNull(index) as? EditorTextBlock ?: return null
    if (index == 0) return null
    return when (val previous = blocks[index - 1]) {
        is EditorTextBlock -> {
            val separator = if (current.splitFromPrevious) "" else "\n"
            val joined = previous.value.text + separator + current.value.text
            val cursorAt = previous.value.text.length + separator.length
            val mutable = blocks.toMutableList()
            mutable[index - 1] = EditorTextBlock(
                previous.id,
                TextFieldValue(joined, TextRange(cursorAt)),
                previous.splitFromPrevious
            )
            mutable.removeAt(index)
            mutable to previous.id
        }
        is EditorImageBlock -> removeImageAndMerge(blocks, previous.id)
    }
}
