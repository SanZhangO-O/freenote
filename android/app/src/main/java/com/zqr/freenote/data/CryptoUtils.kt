package com.zqr.freenote.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 字段级 AES-GCM 加密，用于锁定便签。
 * 密钥基于用户 PIN 通过 PBKDF2 派生；密文以 Base64 存储，前缀 iv。
 */
object CryptoUtils {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val ITERATION_COUNT = 12000
    private const val KEY_LENGTH = 256
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    private val salt = "freenote-fixed-salt-v1".toByteArray()
    private val random = SecureRandom()

    private fun deriveKey(pin: String): SecretKey {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, KEY_ALGORITHM)
    }

    fun encrypt(plain: String, pin: String): String {
        if (plain.isEmpty()) return ""
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(pin), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = iv + encrypted
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(cipherText: String, pin: String): String? {
        if (cipherText.isEmpty()) return ""
        return try {
            val combined = Base64.getDecoder().decode(cipherText)
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(pin), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
