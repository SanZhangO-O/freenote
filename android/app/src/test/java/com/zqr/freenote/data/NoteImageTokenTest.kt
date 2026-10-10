package com.zqr.freenote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteImageTokenTest {

    @Test
    fun parsesAndStripsInlineImageTokens() {
        // 标记与路径往返
        val path = "/data/user/0/com.zqr.freenote/files/note_images/ab12_img.jpg"
        val token = imageToken(path)
        assertTrue(isImageTokenLine(token))
        assertEquals("file://$path", imageTokenPath(token))
        assertEquals(listOf(path), parseImageTokens(token).map(::localImagePath))

        // 按出现顺序解析多张图片
        val content = """
            第一段

            ${imageToken("/a.png")}
            中间文字
            ${imageToken("/b.png")}
        """.trimIndent()
        assertEquals(listOf("/a.png", "/b.png"), parseImageTokens(content).map(::localImagePath))

        // 非图片行：标记必须独占一行
        assertNull(imageTokenPath("![图片](/a.png) 尾部文字"))
        assertNull(imageTokenPath("请看 ![图片](/a.png)"))
        assertNull(imageTokenPath("![未闭合(/a.png"))
        assertFalse(isImageTokenLine("# 标题"))

        // 预览用替换：连续多张图合并为一行
        val contiguous = """
            ${imageToken("/a.png")}
            标题行
            ${imageToken("/b.png")}
            ${imageToken("/c.png")}
            结尾
        """.trimIndent()
        assertEquals("[图片]\n标题行\n[图片]\n结尾", stripImageTokens(contiguous, "[图片]"))

        // 纯图片内容与普通文本
        val onlyImages = "${imageToken("/a.png")}\n${imageToken("/b.png")}"
        assertEquals("[图片]", stripImageTokens(onlyImages, "[图片]"))
        assertEquals("", stripImageTokens(onlyImages))
        assertEquals("第一行\n\n第二行", stripImageTokens("第一行\n\n第二行", "[图片]"))
    }
}
