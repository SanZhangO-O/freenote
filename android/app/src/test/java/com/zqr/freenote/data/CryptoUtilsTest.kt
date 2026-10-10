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
}
