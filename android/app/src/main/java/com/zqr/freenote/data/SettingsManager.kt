package com.zqr.freenote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val pinHash: String? = null
)

object SettingsManager {
    private const val PREFS_NAME = "freenote_settings"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_DYNAMIC = "dynamic_color"
    private const val KEY_PIN = "pin_hash"
    private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
    private const val KEY_AUTO_BACKUP_TREE = "auto_backup_tree"
    private const val KEY_LAST_AUTO_BACKUP = "last_auto_backup_at"
    private const val KEY_WEBDAV_URL = "webdav_url"
    private const val KEY_WEBDAV_USER = "webdav_user"
    private const val KEY_WEBDAV_PASSWORD = "webdav_password"

    private const val PIN_ITERATIONS = 60_000
    private const val PIN_SALT_BYTES = 16

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
        initialized = true
    }

    // ------------------------------------------------------------------ 自动备份 / WebDAV

    fun autoBackupEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, false)

    fun setAutoBackupEnabled(value: Boolean) =
        prefs.edit().putBoolean(KEY_AUTO_BACKUP_ENABLED, value).apply()

    /** SAF 目录 Tree URI（用户选择的自动备份文件夹；空串 = 未设置）。 */
    fun autoBackupTree(): String = prefs.getString(KEY_AUTO_BACKUP_TREE, "") ?: ""

    fun setAutoBackupTree(uri: String) =
        prefs.edit().putString(KEY_AUTO_BACKUP_TREE, uri).apply()

    fun lastAutoBackupAt(): Long = prefs.getLong(KEY_LAST_AUTO_BACKUP, 0L)

    fun setLastAutoBackupAt(value: Long) =
        prefs.edit().putLong(KEY_LAST_AUTO_BACKUP, value).apply()

    fun webdavUrl(): String = prefs.getString(KEY_WEBDAV_URL, "") ?: ""

    fun setWebdavUrl(value: String) = prefs.edit().putString(KEY_WEBDAV_URL, value.trim()).apply()

    fun webdavUser(): String = prefs.getString(KEY_WEBDAV_USER, "") ?: ""

    fun setWebdavUser(value: String) =
        prefs.edit().putString(KEY_WEBDAV_USER, value.trim()).apply()

    fun webdavPassword(): String = prefs.getString(KEY_WEBDAV_PASSWORD, "") ?: ""

    fun setWebdavPassword(value: String) =
        prefs.edit().putString(KEY_WEBDAV_PASSWORD, value).apply()

    private fun load() {
        val mode = when (prefs.getString(KEY_THEME, "SYSTEM")) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
        _settings.value = AppSettings(
            themeMode = mode,
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
            pinHash = prefs.getString(KEY_PIN, null)
        )
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        // 原子 CAS 写入：setPin 可能从后台线程并发更新同一 StateFlow
        _settings.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC, enabled) }
        _settings.update { it.copy(dynamicColor = enabled) }
    }

    fun setPin(pin: String) {
        val hash = hashPin(pin)
        prefs.edit { putString(KEY_PIN, hash) }
        _settings.update { it.copy(pinHash = hash) }
    }

    fun clearPin() {
        prefs.edit { remove(KEY_PIN) }
        _settings.update { it.copy(pinHash = null) }
    }

    fun verifyPin(pin: String): Boolean {
        val stored = _settings.value.pinHash ?: return false
        val parts = stored.split(":")
        if (parts.size != 3) return false
        val iterations = parts[0].toIntOrNull() ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val derived = derivePinKey(pin, salt, iterations)
        return MessageDigest.isEqual(derived, expected)
    }

    fun hasPin(): Boolean = _settings.value.pinHash != null

    /**
     * 随机盐 + PBKDF2 派生 PIN 哈希，避免固定盐 + 单次 SHA-256 可被离线批量爆破。
     * 存储格式："迭代次数:盐(Base64):派生密钥(Base64)"。
     */
    private fun hashPin(pin: String): String {
        val salt = ByteArray(PIN_SALT_BYTES).also(SecureRandom()::nextBytes)
        val derived = derivePinKey(pin, salt, PIN_ITERATIONS)
        return "$PIN_ITERATIONS:${Base64.getEncoder().encodeToString(salt)}:" +
            Base64.getEncoder().encodeToString(derived)
    }

    private fun derivePinKey(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}
