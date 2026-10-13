package com.zqr.freenote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.zqr.freenote.data.sync.SecretStore
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
    // WebDAV 密码单独存放（Keystore 加密 + 从备份/换机迁移中排除）
    private const val SECRETS_PREFS_NAME = "freenote_backup_secrets"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_DYNAMIC = "dynamic_color"
    private const val KEY_PIN = "pin_hash"
    private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
    private const val KEY_AUTO_BACKUP_TREE = "auto_backup_tree"
    private const val KEY_LAST_AUTO_BACKUP = "last_auto_backup_at"
    private const val KEY_WEBDAV_URL = "webdav_url"
    private const val KEY_WEBDAV_USER = "webdav_user"
    // 旧版本此键存明文密码（仅用于迁移）；新值加密后存入 SECRETS_PREFS_NAME
    private const val KEY_WEBDAV_PASSWORD_LEGACY = "webdav_password"
    private const val KEY_WEBDAV_PASSWORD_SECRET = "webdav_password"

    private const val PIN_ITERATIONS = 600_000
    private const val PIN_SALT_BYTES = 16

    private lateinit var prefs: SharedPreferences
    private lateinit var secretsPrefs: SharedPreferences
    private var initialized = false

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        secretsPrefs = appContext.getSharedPreferences(SECRETS_PREFS_NAME, Context.MODE_PRIVATE)
        migrateWebdavPassword()
        load()
        initialized = true
    }

    /** 旧版本把 WebDAV 密码明文存在 freenote_settings.xml：迁移为 Keystore 密文并删除明文。 */
    private fun migrateWebdavPassword() {
        val legacy = prefs.getString(KEY_WEBDAV_PASSWORD_LEGACY, null) ?: return
        if (legacy.isEmpty()) {
            prefs.edit { remove(KEY_WEBDAV_PASSWORD_LEGACY) }
            return
        }
        val encrypted = SecretStore.encrypt(legacy)
        if (encrypted.isNotEmpty()) {
            secretsPrefs.edit { putString(KEY_WEBDAV_PASSWORD_SECRET, encrypted) }
            prefs.edit { remove(KEY_WEBDAV_PASSWORD_LEGACY) }
        }
        // 加密不可用（Keystore 异常）时保留明文项，下次启动重试，绝不静默丢密码
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

    fun webdavPassword(): String =
        SecretStore.decrypt(secretsPrefs.getString(KEY_WEBDAV_PASSWORD_SECRET, "") ?: "")

    fun setWebdavPassword(value: String) =
        secretsPrefs.edit { putString(KEY_WEBDAV_PASSWORD_SECRET, SecretStore.encrypt(value)) }

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
     * 随机盐 + PBKDF2 派生 PIN 哈希（600000 次，与 Windows 版一致），
     * 避免低迭代 + 固定盐可被离线批量爆破。
     * 存储格式："迭代次数:盐(Base64):派生密钥(Base64)"（旧哈希按其中记录的迭代数校验）。
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
