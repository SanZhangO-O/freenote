package com.zqr.freenote.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorBlockOpsTest {

    private fun text(id: String, value: String, cursor: Int = value.length) =
        EditorTextBlock(id, TextFieldValue(value, TextRange(cursor)))

    private fun flaggedText(id: String, value: String, cursor: Int = value.length) =
        EditorTextBlock(id, TextFieldValue(value, TextRange(cursor)), splitFromPrevious = true)

    private fun image(id: String, path: String = "img_$id.jpg") = EditorImageBlock(id, path)

    @Test
    fun insertAtCursorMiddleSplitsTextAroundImage() {
        val blocks = listOf(text("t1", "abcdef", cursor = 3))
        val (result, focusId) = insertImageBlocks(blocks, "t1", listOf("p.jpg"))

        assertEquals(3, result.size)
        assertEquals("abc", (result[0] as EditorTextBlock).value.text)
        assertEquals("p.jpg", (result[1] as EditorImageBlock).path)
        assertEquals("def", (result[2] as EditorTextBlock).value.text)
        // 焦点落到图片后的文字块，光标在开头（紧接图片）
        assertEquals(result[2].id, focusId)
        assertEquals(0, (result[2] as EditorTextBlock).value.selection.start)
    }

    @Test
    fun insertAtCursorStartPutsImageBeforeText() {
        val blocks = listOf(text("t1", "正文", cursor = 0))
        val (result, focusId) = insertImageBlocks(blocks, "t1", listOf("p.jpg"))

        assertEquals(2, result.size)
        assertEquals("p.jpg", (result[0] as EditorImageBlock).path)
        assertEquals("正文", (result[1] as EditorTextBlock).value.text)
        assertEquals(result[1].id, focusId)
    }

    @Test
    fun insertAtCursorEndLeavesEmptyContinueBlock() {
        val blocks = listOf(text("t1", "abc", cursor = 3))
        val (result, focusId) = insertImageBlocks(blocks, "t1", listOf("p.jpg"))

        assertEquals(3, result.size)
        assertEquals("abc", (result[0] as EditorTextBlock).value.text)
        assertEquals("p.jpg", (result[1] as EditorImageBlock).path)
        // 图片后的空文字块作为继续输入的落点
        assertEquals("", (result[2] as EditorTextBlock).value.text)
        assertEquals(result[2].id, focusId)
    }

    @Test
    fun insertWithoutFocusedBlockAppendsToEndAndKeepsFocusNull() {
        val blocks = listOf(text("t1", "abc"), text("t2", "def"))
        val (result, focusId) = insertImageBlocks(blocks, null, listOf("p.jpg"))

        assertEquals(3, result.size)
        assertEquals("p.jpg", (result[2] as EditorImageBlock).path)
        assertNull(focusId)
    }

    @Test
    fun insertWithoutFocusBeforeTrailingBlankTextBlock() {
        // 新建便签：唯一块是空文字块，图片应插到它前面，输入区保持在图片下方
        val blocks = listOf(text("t1", ""))
        val (result, focusId) = insertImageBlocks(blocks, null, listOf("p.jpg"))

        assertEquals(2, result.size)
        assertEquals("p.jpg", (result[0] as EditorImageBlock).path)
        assertEquals("t1", result[1].id)
        assertNull(focusId)
    }

    @Test
    fun insertWithUnknownFocusBlockAppendsToEnd() {
        val blocks = listOf(text("t1", "abc"))
        val (result, focusId) = insertImageBlocks(blocks, "missing", listOf("p.jpg"))

        assertEquals(2, result.size)
        assertEquals("p.jpg", (result[1] as EditorImageBlock).path)
        assertNull(focusId)
    }

    @Test
    fun multipleImagesKeepChosenOrderBetweenSplitText() {
        val blocks = listOf(text("t1", "abcd", cursor = 2))
        val (result, focusId) =
            insertImageBlocks(blocks, "t1", listOf("one.jpg", "two.jpg", "three.jpg"))

        // 光标前文字 + 3 张图片 + 光标后文字
        assertEquals(5, result.size)
        assertEquals("ab", (result[0] as EditorTextBlock).value.text)
        assertEquals("one.jpg", (result[1] as EditorImageBlock).path)
        assertEquals("two.jpg", (result[2] as EditorImageBlock).path)
        assertEquals("three.jpg", (result[3] as EditorImageBlock).path)
        // 焦点在图片之后的文字块（最后一个块），光标在开头
        assertEquals(result.last().id, focusId)
        assertEquals("cd", (result.last() as EditorTextBlock).value.text)
        assertEquals(0, (result.last() as EditorTextBlock).value.selection.start)
    }

    @Test
    fun insertEmptyPathListReturnsOriginalBlocks() {
        val blocks = listOf(text("t1", "abc"))
        val (result, focusId) = insertImageBlocks(blocks, "t1", emptyList())

        assertEquals(blocks, result)
        assertNull(focusId)
    }

    @Test
    fun insertMarksContinuationBlockAsSplit() {
        val blocks = listOf(text("t1", "abcdef", cursor = 3))
        val (result, _) = insertImageBlocks(blocks, "t1", listOf("p.jpg"))

        // 前半段无拆分标记，后半段由这次插入切开
        assertFalse((result[0] as EditorTextBlock).splitFromPrevious)
        assertTrue((result[2] as EditorTextBlock).splitFromPrevious)
    }

    @Test
    fun insertKeepsAnchorFlagOnBeforeBlock() {
        val anchor = flaggedText("t1", "abcdef", cursor = 3)
        val (result, _) = insertImageBlocks(listOf(anchor), "t1", listOf("p.jpg"))

        // 前半段继承锚点块的拆分标记（与更上方图片的关系不变）
        assertTrue((result[0] as EditorTextBlock).splitFromPrevious)
        assertTrue((result[2] as EditorTextBlock).splitFromPrevious)
    }

    @Test
    fun removeImageMergesCursorSplitWithoutNewline() {
        val blocks = listOf(text("t1", "ab"), image("i1"), flaggedText("t2", "cd"))
        val (result, focusId) = removeImageAndMerge(blocks, "i1")

        // 插入时切开的正文不引入换行：删除图片后拼回原文，光标落在拼接处
        assertEquals(1, result.size)
        val merged = result[0] as EditorTextBlock
        assertEquals("t1", merged.id)
        assertEquals("abcd", merged.value.text)
        assertEquals(2, merged.value.selection.start)
        assertEquals("t1", focusId)
    }

    @Test
    fun removeImageKeepsOriginalLineBreakBetweenBlocks() {
        // 两侧文字块来自重建（原本就是两行，如 Windows 端同步来的标记行）：
        // 删除图片后保留换行
        val blocks = listOf(text("t1", "第一行"), image("i1"), text("t2", "第二行"))
        val (result, focusId) = removeImageAndMerge(blocks, "i1")

        assertEquals(1, result.size)
        assertEquals("第一行\n第二行", (result[0] as EditorTextBlock).value.text)
        assertEquals("t1", focusId)
    }

    @Test
    fun legacyContentAfterImageRemovalSerializesSameAsOldVersion() {
        // 旧版兼容：已存储的正文（本会话未做过拆分插入）重建出的文字块不带拆分标记，
        // 删除图片后的序列化结果必须与旧版完全一致——保留换行，不产生新格式
        val blocks = listOf(text("t1", "第一行"), image("i1"), text("t2", "第二行"))
        val (result, _) = removeImageAndMerge(blocks, "i1")

        // 与 NoteEditViewModel.serializeBlocks 相同的规则：非空文字块/图片标记按 \n 连接
        val serialized = result.mapNotNull { block ->
            when (block) {
                is EditorTextBlock -> block.value.text.takeIf { it.isNotBlank() }
                is EditorImageBlock -> "![](${block.path})"
            }
        }.joinToString("\n")

        assertEquals("第一行\n第二行", serialized)
    }

    @Test
    fun legacyContentWithBlankLinesRoundTripsUnchanged() {
        // 旧版兼容：含空行与标记行的正文，重建 → 序列化必须原样还原（本次改动未触碰该路径）
        val legacy = listOf(
            text("t1", "第一行\n\n第二行"),
            image("i1"),
            text("t2", "第三行")
        )
        val serialized = legacy.joinToString("\n") { block ->
            when (block) {
                is EditorTextBlock -> block.value.text
                is EditorImageBlock -> "![](${block.path})"
            }
        }

        assertEquals("第一行\n\n第二行\n![](img_i1.jpg)\n第三行", serialized)
    }

    @Test
    fun removeImageRestoresMultilineBeforeBlock() {
        // 拆分点在行尾时，前半段自带换行：拼接后换行原样保留
        val blocks = listOf(text("t1", "一\n二\n"), image("i1"), flaggedText("t2", "三"))
        val (result, _) = removeImageAndMerge(blocks, "i1")

        assertEquals(1, result.size)
        val merged = result[0] as EditorTextBlock
        assertEquals("一\n二\n三", merged.value.text)
        assertEquals(4, merged.value.selection.start)
    }

    @Test
    fun removeImageAtEndJoinsTrailingBlankIntoPrevious() {
        val blocks = listOf(text("t1", "abc"), image("i1"), flaggedText("t2", ""))
        val (result, focusId) = removeImageAndMerge(blocks, "i1")

        assertEquals(1, result.size)
        assertEquals("abc", (result[0] as EditorTextBlock).value.text)
        assertEquals("t1", focusId)
    }

    @Test
    fun removeImageBetweenMultipleImagesDoesNotMergeAcrossImage() {
        val blocks = listOf(text("t1", "a"), image("i1"), text("t2", "b"), image("i2"), text("t3", "c"))
        val (result, focusId) = removeImageAndMerge(blocks, "i1")

        // 只删除 i1：两侧文字块按原两行拼回；i2、t3 不受影响
        assertEquals(3, result.size)
        assertEquals("a\nb", (result[0] as EditorTextBlock).value.text)
        assertEquals("i2", result[1].id)
        assertEquals("c", (result[2] as EditorTextBlock).value.text)
        assertEquals("t1", focusId)
    }

    @Test
    fun removeImageWhenImageIsFirstOrLastBlock() {
        val (firstResult, firstFocus) = removeImageAndMerge(listOf(image("i1"), text("t2", "abc")), "i1")
        assertEquals(listOf("t2"), firstResult.map { it.id })
        assertNull(firstFocus)

        val (lastResult, lastFocus) = removeImageAndMerge(listOf(text("t1", "abc"), image("i2")), "i2")
        assertEquals(listOf("t1"), lastResult.map { it.id })
        assertNull(lastFocus)
    }

    @Test
    fun removeOnlyImageLeavesSingleEmptyTextBlock() {
        val (result, focusId) = removeImageAndMerge(listOf(image("i1")), "i1")

        assertEquals(1, result.size)
        assertTrue(result[0] is EditorTextBlock)
        assertEquals("", (result[0] as EditorTextBlock).value.text)
        assertNull(focusId)
    }

    @Test
    fun removeUnknownImageReturnsOriginalBlocks() {
        val blocks = listOf(text("t1", "abc"), image("i1"))
        val (result, focusId) = removeImageAndMerge(blocks, "missing")

        assertEquals(blocks, result)
        assertNull(focusId)
    }

    @Test
    fun removeImageRoundTripsWithInsert() {
        val (inserted, _) = insertImageBlocks(listOf(text("t1", "abcdef", cursor = 3)), "t1", listOf("p.jpg"))
        val imageId = inserted[1].id

        val (restored, _) = removeImageAndMerge(inserted, imageId)

        // 插入 → 删除图片：正文拼回原样，不留换行残留
        assertEquals(1, restored.size)
        assertEquals("abcdef", (restored[0] as EditorTextBlock).value.text)
    }

    @Test
    fun mergeBackwardJoinsIntoPreviousTextBlock() {
        val blocks = listOf(text("t1", "ab"), flaggedText("t2", "cd"))
        val (result, focusId) = mergeBackwardAtStart(blocks, "t2")!!

        assertEquals(1, result.size)
        val merged = result[0] as EditorTextBlock
        assertEquals("t1", merged.id)
        assertEquals("abcd", merged.value.text)
        assertEquals(2, merged.value.selection.start)
        assertEquals("t1", focusId)
    }

    @Test
    fun mergeBackwardAcrossOriginalLineBreakKeepsNewline() {
        val blocks = listOf(text("t1", "ab"), text("t2", "cd"))
        val (result, focusId) = mergeBackwardAtStart(blocks, "t2")!!

        assertEquals(1, result.size)
        assertEquals("ab\ncd", (result[0] as EditorTextBlock).value.text)
        assertEquals("t1", focusId)
    }

    @Test
    fun mergeBackwardRemovesPreviousImageAndMergesSides() {
        val blocks = listOf(text("t1", "ab"), image("i1"), flaggedText("t2", "cd"))
        val (result, focusId) = mergeBackwardAtStart(blocks, "t2")!!

        assertEquals(1, result.size)
        assertEquals("abcd", (result[0] as EditorTextBlock).value.text)
        assertEquals("t1", focusId)
    }

    @Test
    fun mergeBackwardAfterImageKeepsFocusOnSurvivingBlock() {
        // 图片是第一个块：删除后当前块保留，无需转移焦点
        val blocks = listOf(image("i1"), flaggedText("t2", "abc"))
        val (result, focusId) = mergeBackwardAtStart(blocks, "t2")!!

        assertEquals(listOf("t2"), result.map { it.id })
        assertNull(focusId)
        assertEquals("abc", (result[0] as EditorTextBlock).value.text)
    }

    @Test
    fun mergeBackwardOnFirstBlockReturnsNull() {
        val blocks = listOf(text("t1", "abc"))
        assertNull(mergeBackwardAtStart(blocks, "t1"))
    }

    @Test
    fun mergeBackwardWithUnknownIdReturnsNull() {
        assertNull(mergeBackwardAtStart(listOf(text("t1", "a")), "missing"))
    }

    @Test
    fun mergeBackwardOnImageBlockReturnsNull() {
        val blocks = listOf(text("t1", "a"), image("i1"))
        assertNull(mergeBackwardAtStart(blocks, "i1"))
    }

    @Test
    fun moveBlockRelocatesImageBetweenTextBlocks() {
        val blocks = listOf(text("t1", "甲"), image("i1"), text("t2", "乙"), text("t3", "丙"))
        val moved = moveBlockInList(blocks, "i1", 3)!!

        assertEquals(listOf("t1", "t2", "t3", "i1"), moved.map { it.id })
        // 原列表未被修改
        assertEquals(listOf("t1", "i1", "t2", "t3"), blocks.map { it.id })
    }

    @Test
    fun moveBlockUpward() {
        val blocks = listOf(text("t1", "甲"), text("t2", "乙"), image("i1"))
        val moved = moveBlockInList(blocks, "i1", 0)!!

        assertEquals(listOf("i1", "t1", "t2"), moved.map { it.id })
    }

    @Test
    fun moveBlockInvalidArgumentsReturnNull() {
        val blocks = listOf(text("t1", "甲"), image("i1"))
        assertNull(moveBlockInList(blocks, "missing", 0))
        assertNull(moveBlockInList(blocks, "i1", -1))
        assertNull(moveBlockInList(blocks, "i1", 2))
        assertNull(moveBlockInList(blocks, "i1", 1))
    }
}
