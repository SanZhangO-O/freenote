package com.zqr.freenote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoUtilsTest {

    @Test
    fun noteCryptoRoundTripAndFailureModes() {
        // 中文 / emoji / 多行 / 空串
        for (plain in listOf(
            "今天天气不错，记得买牛奶 #list",
            "中文测试 ✓ 🎉 emoji\n第二行",
            "multi\nline\n文本"
        )) {
            val cipher = CryptoUtils.encrypt(plain, "1234")
            assertFalse(cipher.contains(plain))
            assertEquals(plain, CryptoUtils.decrypt(cipher, "1234"))
        }
        assertEquals("", CryptoUtils.encrypt("", "1234"))
        assertEquals("", CryptoUtils.decrypt("", "1234"))

        // 错误 PIN / 密文被篡改
        val cipher = CryptoUtils.encrypt("秘密内容", "1234")
        assertNull(CryptoUtils.decrypt(cipher, "9999"))
        assertNull(CryptoUtils.decrypt(cipher, "12345"))
        val tampered = if (cipher.first() == 'A') "B" + cipher.drop(1) else "A" + cipher.drop(1)
        assertNull(CryptoUtils.decrypt(tampered, "1234"))

        // 每次加密使用随机 IV，且密文不含明文
        val a = CryptoUtils.encrypt("same", "1234")
        val b = CryptoUtils.encrypt("same", "1234")
        assertNotEquals(a, b)
        assertEquals("same", CryptoUtils.decrypt(a, "1234"))
        assertEquals("same", CryptoUtils.decrypt(b, "1234"))
        val secret = "绝密内容 never-in-plain"
        val secretCipher = CryptoUtils.encrypt(secret, "1234")
        assertFalse(secretCipher.contains("绝密"))
        assertFalse(secretCipher.contains("never-in-plain"))

        // 长内容
        val long = (1..500).joinToString("") { "第${it}行内容，" }
        assertEquals(long, CryptoUtils.decrypt(CryptoUtils.encrypt(long, "5678"), "5678"))
    }

    @Test
    fun decryptsLegacyAndV2CrossPlatformVectors() {
        // 跨端固定向量（Windows crypto.py 以相同参数生成，保证两端互通）
        val legacy = "AAECAwQFBgcICQoLtIH1Ta4QTHljJ3yYMc36WjNOoJq8fvKK8lCJ4Z7G9MXlU74ZHg=="
        assertEquals("旧格式密文内容", CryptoUtils.decrypt(legacy, "1234"))
        val v2 = "v2:600000:AAECAwQFBgcICQoLDA0ODwABAgMEBQYHCAkKC7id7RckXYRzVC35nH5MJSCG" +
            "WQpI8FvASnq8P/HAAwvZUB2f53Sq"
        assertEquals("跨端向量 v2 内容", CryptoUtils.decrypt(v2, "1234"))

        // 新密文使用 v2 格式（随机盐 + 600000 次），同明文两次加密不同
        val cipher = CryptoUtils.encrypt("v2 格式内容", "1234")
        assertTrue(cipher.startsWith("v2:600000:"))
        assertEquals("v2 格式内容", CryptoUtils.decrypt(cipher, "1234"))
        assertNotEquals(cipher, CryptoUtils.encrypt("v2 格式内容", "1234"))

        // 畸形 v2：非法迭代数/不可解码载荷一律拒绝，不因巨量迭代数空转 CPU
        assertNull(CryptoUtils.decrypt("v2:999999999999:" + "A".repeat(64), "1234"))
        assertNull(CryptoUtils.decrypt("v2:600000:@@@", "1234"))
    }
}
