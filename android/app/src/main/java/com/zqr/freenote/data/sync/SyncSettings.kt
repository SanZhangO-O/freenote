package com.zqr.freenote.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** 已发现/已添加的 P2P 设备。 */
data class SyncPeer(
    val id: String = "",
    val name: String = "",
    val address: String = "",
    val port: Int = DEFAULT_SYNC_PORT,
    val manual: Boolean = false,
    val digest: String = "",
    val account: String = "",
    val lastSeen: Long = 0,
    val lastSyncAt: Long = 0,
    val lastStatus: String = ""
) {
    fun displayName(): String =
        name.ifBlank { if (manual) "手动添加" else address.ifBlank { id } }

    fun addressText(): String =
        if (address.isBlank()) "?" else "$address:$port"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("address", address)
        .put("port", port)
        .put("manual", manual)
        .put("digest", digest)
        .put("account", account)
        .put("last_seen", lastSeen)
        .put("last_sync_at", lastSyncAt)
        .put("last_status", lastStatus)

    companion object {
        fun fromJson(obj: JSONObject): SyncPeer = SyncPeer(
            id = obj.optString("id"),
            name = obj.optString("name"),
            address = obj.optString("address"),
            port = obj.optInt("port", DEFAULT_SYNC_PORT),
            manual = obj.optBoolean("manual"),
            digest = obj.optString("digest"),
            account = obj.optString("account"),
            lastSeen = obj.optLong("last_seen"),
            lastSyncAt = obj.optLong("last_sync_at"),
            lastStatus = obj.optString("last_status")
        )
    }
}

data class SyncConfig(
    val deviceId: String = "",
    val deviceName: String = "",
    val passphrase: String = "",
    val p2pAccount: String = "",
    // 网络功能默认全部关闭：P2P 总开关/发现/自动同步需用户显式开启；
    // 总开关未开启或未配置口令前不监听端口、不参与发现（见 SyncManager.startP2P）
    val p2pEnabled: Boolean = false,
    val discoveryEnabled: Boolean = false,
    val p2pAutoSync: Boolean = false,
    val syncPort: Int = DEFAULT_SYNC_PORT,
    val syncIntervalMin: Int = 5,
    val serverUrl: String = "",
    val username: String = "",
    val token: String = "",
    val serverAutoSync: Boolean = false,
    /** HTTPS 自签名证书：开启后跳过证书校验（传输仍加密，但不验证服务器身份）。 */
    val serverAllowSelfSigned: Boolean = false
) {
    val p2pReady: Boolean get() = passphrase.isNotBlank()
    val serverReady: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}

const val DEFAULT_SYNC_PORT = 8765

/**
 * 同步设置（SharedPreferences，独立于界面设置）。
 * 与 FreeNote 的 settings.json 对应：P2P 口令/设备名/端口/发现开关 + 服务器账号。
 * 所有联网开关（P2P 总开关/发现/P2P 自动同步/服务器自动同步）默认关闭，由用户显式开启。
 */
object SyncSettings {
    private const val PREFS_NAME = "freenote_sync"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_PASSPHRASE = "passphrase"
    private const val KEY_P2P_ACCOUNT = "p2p_account"
    private const val KEY_P2P_ENABLED = "p2p_enabled"
    private const val KEY_DISCOVERY = "discovery_enabled"
    private const val KEY_P2P_AUTO = "p2p_auto_sync"
    private const val KEY_SYNC_PORT = "sync_port"
    private const val KEY_INTERVAL = "sync_interval_min"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_TOKEN = "token"
    private const val KEY_SERVER_AUTO = "server_auto_sync"
    private const val KEY_SERVER_SELF_SIGNED = "server_allow_self_signed"
    private const val KEY_PEERS = "peers"

    private lateinit var prefs: SharedPreferences
    @Volatile private var initialized = false

    private val _config = MutableStateFlow(SyncConfig())
    val config: StateFlow<SyncConfig> = _config

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
        initialized = true
    }

    private fun load() {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: ""
        if (deviceId.isBlank()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit { putString(KEY_DEVICE_ID, deviceId) }
        }
        var deviceName = prefs.getString(KEY_DEVICE_NAME, null) ?: ""
        if (deviceName.isBlank()) {
            deviceName = defaultDeviceName()
            prefs.edit { putString(KEY_DEVICE_NAME, deviceName) }
        }
        _config.value = SyncConfig(
            deviceId = deviceId,
            deviceName = deviceName,
            passphrase = SecretStore.decrypt(prefs.getString(KEY_PASSPHRASE, "") ?: ""),
            p2pAccount = prefs.getString(KEY_P2P_ACCOUNT, "") ?: "",
            p2pEnabled = prefs.getBoolean(KEY_P2P_ENABLED, false),
            discoveryEnabled = prefs.getBoolean(KEY_DISCOVERY, false),
            p2pAutoSync = prefs.getBoolean(KEY_P2P_AUTO, false),
            syncPort = prefs.getInt(KEY_SYNC_PORT, DEFAULT_SYNC_PORT),
            syncIntervalMin = maxOf(1, prefs.getInt(KEY_INTERVAL, 5)),
            serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: "",
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            token = SecretStore.decrypt(prefs.getString(KEY_TOKEN, "") ?: ""),
            serverAutoSync = prefs.getBoolean(KEY_SERVER_AUTO, false),
            serverAllowSelfSigned = prefs.getBoolean(KEY_SERVER_SELF_SIGNED, false)
        )
    }

    private fun defaultDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isBlank()) "Android 设备" else model.take(64)
    }

    fun setDeviceName(value: String) {
        val name = value.trim().ifBlank { defaultDeviceName() }.take(64)
        prefs.edit { putString(KEY_DEVICE_NAME, name) }
        _config.update { it.copy(deviceName = name) }
    }

    fun setPassphrase(value: String) {
        val passphrase = value.trim().take(256)
        prefs.edit { putString(KEY_PASSPHRASE, SecretStore.encrypt(passphrase)) }
        _config.update { it.copy(passphrase = passphrase) }
    }

    /** 离线账户名：仅在未配置服务器账号时作为 P2P 同步账户。 */
    fun setP2pAccount(value: String) {
        val account = value.trim().take(64)
        prefs.edit { putString(KEY_P2P_ACCOUNT, account) }
        _config.update { it.copy(p2pAccount = account) }
    }

    /** P2P 总开关：默认关闭，开启且配置口令后才监听端口、参与发现。 */
    fun setP2pEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_P2P_ENABLED, enabled) }
        _config.update { it.copy(p2pEnabled = enabled) }
    }

    fun setDiscoveryEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DISCOVERY, enabled) }
        _config.update { it.copy(discoveryEnabled = enabled) }
    }

    fun setP2pAutoSync(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_P2P_AUTO, enabled) }
        _config.update { it.copy(p2pAutoSync = enabled) }
    }

    fun setSyncPort(port: Int) {
        val value = port.coerceIn(0, 65535)
        prefs.edit { putInt(KEY_SYNC_PORT, value) }
        _config.update { it.copy(syncPort = value) }
    }

    fun setSyncIntervalMin(minutes: Int) {
        val value = maxOf(1, minutes)
        prefs.edit { putInt(KEY_INTERVAL, value) }
        _config.update { it.copy(syncIntervalMin = value) }
    }

    fun setServerUrl(url: String) {
        val value = url.trim().trimEnd('/')
        prefs.edit { putString(KEY_SERVER_URL, value) }
        _config.update { it.copy(serverUrl = value) }
    }

    fun setUsername(username: String) {
        val value = username.trim()
        prefs.edit { putString(KEY_USERNAME, value) }
        _config.update { it.copy(username = value) }
    }

    fun setToken(token: String) {
        prefs.edit { putString(KEY_TOKEN, SecretStore.encrypt(token)) }
        _config.update { it.copy(token = token) }
    }

    fun setServerAutoSync(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SERVER_AUTO, enabled) }
        _config.update { it.copy(serverAutoSync = enabled) }
    }

    fun setServerAllowSelfSigned(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SERVER_SELF_SIGNED, enabled) }
        _config.update { it.copy(serverAllowSelfSigned = enabled) }
    }

    fun peers(): List<SyncPeer> {
        val raw = prefs.getString(KEY_PEERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { SyncPeer.fromJson(it) }
            }.filter { it.id.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setPeers(peers: List<SyncPeer>) {
        val array = JSONArray()
        peers.forEach { array.put(it.toJson()) }
        prefs.edit { putString(KEY_PEERS, array.toString()) }
    }
}
