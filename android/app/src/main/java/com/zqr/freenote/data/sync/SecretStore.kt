package com.zqr.freenote.data.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 同步凭据的静态保护：服务器令牌与 P2P 口令不落盘明文。
 *
 * 用 Android Keystore 中不可导出的 AES-256 设备密钥做 AES-GCM 加密，
 * 密文以 "enc:" + Base64(iv + ciphertext) 存入 SharedPreferences；
 * Keystore 密钥随应用卸载销毁，且备份/其他应用无法还原（应用级隔离）。
 * 加解密一律要求 "enc:" 前缀：Keystore 不可用或解密失败时按空处理，
 * 绝不落盘明文。
 */
object SecretStore {
    private const val TAG = "SecretStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "freenote-sync-secret-v1"
    private const val PREFIX = "enc:"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    @Volatile
    private var cachedKey: SecretKey? = null

    /** 加锁避免并发首次访问时重复生成密钥（后到的生成会因别名已存在而失败）。 */
    @Synchronized
    private fun secretKey(): SecretKey? {
        cachedKey?.let { return it }
        return try {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: generateKey()
            cachedKey = existing
            existing
        } catch (e: Exception) {
            Log.w(TAG, "keystore unavailable: ${e.message}")
            null
        }
    }

    private fun generateKey(): SecretKey? {
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        } catch (e: Exception) {
            Log.w(TAG, "keystore key generation failed: ${e.message}")
            null
        }
    }

    /** 加密凭据；Keystore 不可用时返回空串（凭据不落盘明文）。 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val key = secretKey() ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv ?: return ""
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.getEncoder().encodeToString(iv + encrypted)
        } catch (e: Exception) {
            Log.w(TAG, "encrypt failed: ${e.message}")
            ""
        }
    }

    /** 解密凭据；无前缀或解密失败一律返回空串（避免把密文当口令用）。 */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return ""
        val key = secretKey() ?: return ""
        return try {
            val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            if (raw.size <= IV_LENGTH) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH))
            String(cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "decrypt failed: ${e.message}")
            ""
        }
    }
}
