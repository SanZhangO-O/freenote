package com.zqr.freenote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文件夹路径规范化/层级语义的纯 JVM 测试（与 Windows models.py 规则一致）。 */
class FolderPathTest {

    @Test
    fun normalizeAndTraverse() {
        assertEquals("工作/项目A/子", normalizeFolder("  工作 \\ 项目A // 子 "))
        assertEquals("", normalizeFolder("///"))
        assertEquals("工作", folderParent("工作/项目A"))
        assertEquals("", folderParent("工作"))
        assertEquals("", folderParent(""))
        assertEquals(listOf("a", "a/b", "a/b/c"), folderAncestors("a/b/c"))
        assertEquals(emptyList<String>(), folderAncestors(""))
        assertTrue(folderInSubtree("工作/项目A", "工作"))
        assertTrue(folderInSubtree("工作", "工作"))
        assertFalse(folderInSubtree("工作2", "工作"))
        assertTrue(folderInSubtree("", ""))
        assertFalse(folderInSubtree("工作", ""))

        // 目录序：父级在前，子级紧随其后，同级不区分大小写
        val sorted = listOf("b", "a/b", "A", "生活").sortedBy(::folderSortKey)
        assertEquals(listOf("A", "a/b", "b", "生活"), sorted)
    }
}
