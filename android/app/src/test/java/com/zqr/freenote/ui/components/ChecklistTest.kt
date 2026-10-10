package com.zqr.freenote.ui.components

import com.zqr.freenote.data.parseImageTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistTest {

    @Test
    fun parseAndSerializeChecklist() {
        // 标准 Markdown 复选框（大小写 x 都算完成）
        val content = "- [ ] 待办一\n- [x] 已完成\n- [X] 大写完成"
        val items = parseChecklist(content)
        assertEquals(3, items.size)
        assertFalse(items[0].checked)
        assertEquals("待办一", items[0].text)
        assertTrue(items[1].checked)
        assertTrue(items[2].checked)

        // 空行跳过；空内容给占位项
        assertEquals(2, parseChecklist("- [ ] a\n\n- [ ] b").size)
        val blank = parseChecklist("")
        assertEquals(1, blank.size)
        assertEquals("", blank[0].text)
        assertFalse(blank[0].checked)

        // 非清单行按未勾选项处理
        val plain = parseChecklist("普通文本")
        assertEquals(1, plain.size)
        assertFalse(plain[0].checked)
        assertEquals("普通文本", plain[0].text)

        // 序列化保留勾选状态并可往返
        val roundTrip = listOf(
            ChecklistItem(false, "a"),
            ChecklistItem(true, "b"),
            ChecklistItem(false, "c")
        )
        assertEquals(parseChecklist(serializeChecklist(roundTrip)), roundTrip)
        assertEquals("- [x] done", serializeChecklist(listOf(ChecklistItem(true, "done"))))
        assertEquals("- [ ] todo", serializeChecklist(listOf(ChecklistItem(false, "todo"))))
    }

    @Test
    fun markdownToChecklistStripsMarkers() {
        val markdown = "# 标题\n- 项目一\n* 项目二\n> 引用\n**粗体** 与 *斜体* 与 `代码`"
        val items = parseChecklist(markdownToChecklist(markdown))
        assertEquals(
            listOf("标题", "项目一", "项目二", "引用", "粗体 与 斜体 与 代码"),
            items.map { it.text }
        )
        assertTrue(items.none { it.checked })
    }

    @Test
    fun markdownToChecklistDropsBlankLinesAndKeepsCheckedState() {
        val markdown = "- [x] 已完成\n\n   \n- [ ] 待办"
        val items = parseChecklist(markdownToChecklist(markdown))
        assertEquals(listOf("已完成", "待办"), items.map { it.text })
        assertTrue(items[0].checked)
        assertFalse(items[1].checked)
    }

    @Test
    fun markdownToChecklistOnBlankContent() {
        assertEquals("", markdownToChecklist(""))
        assertEquals("", markdownToChecklist("\n\n   \n"))
    }

    @Test
    fun markdownToChecklistSkipsLinesThatBecomeEmpty() {
        // "#" 与 "- " 剥离后为空的行不应产生空白清单项
        assertEquals("- [ ] 有内容", markdownToChecklist("#\n- 有内容\n- "))
    }

    @Test
    fun markdownToChecklistPreservesImageTokens() {
        val image = "file:///data/user/0/com.zqr.freenote/files/note_images/a1.jpg"
        val markdown = "# 标题\n![]($image)\n- 项目一"
        val converted = markdownToChecklist(markdown)
        assertEquals("- [ ] 标题\n![]($image)\n- [ ] 项目一", converted)
        // 图片标记必须仍能被识别：否则保存时会被当成孤儿图片，文件被删除
        assertEquals(listOf(image), parseImageTokens(converted))
    }

    @Test
    fun editingChecklistItemsKeepsImageReferences() {
        val image = "file:///data/user/0/com.zqr.freenote/files/note_images/a1.jpg"
        val content = "- [ ] 任务\n![]($image)\n- [x] 完成"
        val items = parseChecklist(content)
        assertEquals(3, items.size)

        // 勾选普通条目后重新序列化：图片行不能被加上 "- [ ] " 前缀
        val toggled = items.toMutableList().also { it[0] = it[0].copy(checked = true) }
        val reserialized = serializeChecklist(toggled)
        assertEquals("- [x] 任务\n![]($image)\n- [x] 完成", reserialized)
        assertEquals(listOf(image), parseImageTokens(reserialized))
    }

    @Test
    fun markdownToChecklistRepairsPrefixedImageTokens() {
        // 历史数据修复：曾被转成清单项的图片行，重新转换会还原成裸标记行
        val image = "file:///data/user/0/com.zqr.freenote/files/note_images/a1.jpg"
        assertEquals("![]($image)", markdownToChecklist("- [ ] ![]($image)"))
    }
}
