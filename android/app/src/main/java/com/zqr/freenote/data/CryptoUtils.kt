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
 * 字段级 AES-GCM 加密，用于锁定便签，与 Windows 版 `crypto.py` 完全兼容。
 *
 * - 当前格式 v2：每条密文自带随机盐，PBKDF2-HMAC-SHA256 600000 次；
 *   密文 = "v2:" + 迭代数 + ":" + Base64(盐(16) + IV(12) + 密文 + Tag(16))。
 *   随机盐使预计算（彩虹表）失效；迭代数写进密文，便于后续单独提升强度。
 * - 旧格式 v1：固定盐 "freenote-fixed-salt-v1" + 12000 次，Base64(IV + 密文 + Tag)；
 *   仅保留解密兼容（旧版本写入的锁定便签仍能解开），新加密一律使用 v2。
 */
object CryptoUtils {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val KEY_LENGTH = 256
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12
    private const val SALT_LENGTH = 16
    private const val TAG_LENGTH = 16

    private const val NOTE_ITERATIONS = 600_000
    private const val LEGACY_ITERATIONS = 12_000
    // 解密时允许的迭代数范围：防御畸形/恶意密文造成的 CPU 拒绝服务。
    // 上限只留当前默认值的少量余量（未来升级同步改两端），避免同步进来的
    // 恶意密文以迭代数放大 CPU 消耗。
    private const val MIN_ITERATIONS = 1_000
    private const val MAX_ITERATIONS = 1_000_000
    private const val V2_PREFIX = "v2:"

    private val legacySalt = "freenote-fixed-salt-v1".toByteArray()
    private val random = SecureRandom()

    private fun deriveKey(pin: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, KEY_ALGORITHM)
    }

    private fun gcmDecrypt(
        pin: String,
        salt: ByteArray,
        iterations: Int,
        iv: ByteArray,
        encrypted: ByteArray
    ): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(pin, salt, iterations),
            GCMParameterSpec(GCM_TAG_LENGTH, iv)
        )
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    fun encrypt(plain: String, pin: String): String {
        if (plain.isEmpty()) return ""
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(pin, salt, NOTE_ITERATIONS),
            GCMParameterSpec(GCM_TAG_LENGTH, iv)
        )
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val payload = Base64.getEncoder().encodeToString(salt + iv + encrypted)
        return "$V2_PREFIX$NOTE_ITERATIONS:$payload"
    }

    fun decrypt(cipherText: String, pin: String): String? {
        if (cipherText.isEmpty()) return ""
        return try {
            if (cipherText.startsWith(V2_PREFIX)) {
                val parts = cipherText.split(":", limit = 3)
                if (parts.size != 3) return null
                val iterations = parts[1].toIntOrNull() ?: return null
                if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return null
                val raw = Base64.getDecoder().decode(parts[2])
                if (raw.size <= SALT_LENGTH + IV_LENGTH + TAG_LENGTH) return null
                val salt = raw.copyOfRange(0, SALT_LENGTH)
                val iv = raw.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
                val encrypted = raw.copyOfRange(SALT_LENGTH + IV_LENGTH, raw.size)
                gcmDecrypt(pin, salt, iterations, iv, encrypted)
            } else {
                // v1 兼容：固定盐 + 12000 次，Base64(IV + 密文 + Tag)
                val combined = Base64.getDecoder().decode(cipherText)
                if (combined.size <= IV_LENGTH + TAG_LENGTH) return null
                val iv = combined.copyOfRange(0, IV_LENGTH)
                val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)
                gcmDecrypt(pin, legacySalt, LEGACY_ITERATIONS, iv, encrypted)
            }
        } catch (e: Exception) {
            null
        }
    }
}
