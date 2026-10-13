package com.zqr.freenote.data.sync

import android.util.Log
import com.zqr.freenote.data.AttachmentStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ServerSyncException(message: String, val statusCode: Int = 0) : Exception(message)

data class ServerSyncResult(
    val ok: Boolean,
    val message: String,
    val pulled: Int = 0,
    val imagesUploaded: Int = 0,
    val imagesDownloaded: Int = 0,
    val imagesPending: Int = 0
)

data class ServerAuthResult(
    val ok: Boolean,
    val message: String,
    val token: String = "",
    val username: String = ""
)

object ServerUrl {
    /**
     * 补全协议前缀并去掉尾部斜杠。
     * 内网/本机地址默认 http（自建服务器常见形态）；公网域名或 IP 默认 https，
     * 避免用户漏写协议时凭据与便签在公网上走明文。
     */
    fun normalize(text: String): String {
        var value = text.trim().trimEnd('/')
        if (value.isBlank()) return ""
        if (!value.contains("://")) {
            val scheme = if (isPrivateHost(hostOf(value))) "http" else "https"
            value = "$scheme://$value"
        }
        return value
    }

    /** 从「主机[:端口][/路径]」或完整 URL 中提取主机名（小写，去 IPv6 方括号）。 */
    fun hostOf(text: String): String {
        val value = text.trim()
        if (value.isEmpty()) return ""
        if (value.contains("://")) {
            return try {
                java.net.URI(value).host?.lowercase() ?: ""
            } catch (e: Exception) {
                ""
            }
        }
        var authority = value.substringBefore('/').substringAfterLast('@')
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            return if (end > 0) authority.substring(1, end).lowercase() else ""
        }
        if (authority.count { it == ':' } == 1) authority = authority.substringBeforeLast(':')
        return authority.lowercase()
    }

    /** 本机/内网/链路本地主机名（与 Windows config.is_local_host 判定一致）。 */
    fun isPrivateHost(host: String): Boolean {
        val lower = host.trim().lowercase().trim('[', ']')
        if (lower.isEmpty()) return false
        if (lower == "localhost" || lower.endsWith(".local")) return true
        val address = ipLiteralOrNull(lower) ?: return false
        return address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isAnyLocalAddress ||
            isIpv6UniqueLocal(address) ||
            isIpv4CarrierGradeNat(address)
    }

    /** IPv4 运营商级 NAT 100.64.0.0/10（ISP 内网常用，Python is_private 视为私有）。 */
    private fun isIpv4CarrierGradeNat(address: java.net.InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 100 && second in 64..127
    }

    /**
     * 仅当字符串是 IP 字面量时返回解析结果，绝不触发 DNS。
     * 只有严格合法的 IPv4（拒绝前导零，与 Python ipaddress 一致）或以 ':' 开头的
     * IPv6 字面量才会交给 InetAddress，普通域名一律返回 null。
     */
    private fun ipLiteralOrNull(host: String): java.net.InetAddress? {
        if (host.count { it == ':' } >= 2) {
            return try {
                java.net.InetAddress.getByName(host)
            } catch (e: Exception) {
                null
            }
        }
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null  // 前导零：与 Python ipaddress 一致判非法
            val value = part.toInt()
            if (value > 255) return null
            bytes[index] = value.toByte()
        }
        return try {
            java.net.InetAddress.getByAddress(bytes)
        } catch (e: Exception) {
            null
        }
    }

    /** IPv6 唯一本地地址 fc00::/7（InetAddress 没有对应判定，Java 的 ULA 段）。 */
    private fun isIpv6UniqueLocal(address: java.net.InetAddress): Boolean {
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    }

    /**
     * 明文 HTTP 且目标不是本机/内网地址时返回 true
     * （用于提示改用 HTTPS / 阻断 WebDAV 明文上传）。
     */
    fun needsHttpsHint(url: String): Boolean {
        val value = normalize(url)
        if (!value.startsWith("http://", ignoreCase = true)) return false
        val host = hostOf(value)
        if (host.isEmpty()) return false
        return !isPrivateHost(host)
    }

    /** 明文 HTTP 且目标非本机/内网：WebDAV 上传前据此阻断。 */
    fun isPlaintextPublic(url: String): Boolean = needsHttpsHint(url)
}

/**
 * FreeNote 服务端同步客户端（与 server 的 HTTP API 协议一致，
 * 也与 freenote/sync_client.py、freenote/windows/sync_client.py 完全互通）。
 * 协议 v6：/api/sync 附带附件上传/下载（内容寻址，按批传输）。
 */
class ServerSyncClient(
    private val store: SyncStore,
    private val attachments: AttachmentStore
) {

    // ------------------------------------------------------------------ 基础请求

    /** 自签名证书专用：信任所有证书（传输仍加密，但不验证服务器身份）。 */
    private object TrustAll {
        val factory: SSLSocketFactory by lazy {
            val context = SSLContext.getInstance("TLS")
            context.init(
                null,
                arrayOf<X509TrustManager>(
                    object : X509TrustManager {
                        override fun checkClientTrusted(
                            chain: Array<out X509Certificate>?,
                            authType: String?
                        ) {
                        }

                        override fun checkServerTrusted(
                            chain: Array<out X509Certificate>?,
                            authType: String?
                        ) {
                        }

                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                ),
                SecureRandom()
            )
            context.socketFactory
        }
    }

    private fun request(
        method: String,
        baseUrl: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null,
        timeoutMs: Int = 20_000
    ): JSONObject {
        val base = ServerUrl.normalize(baseUrl)
        if (base.isBlank()) throw ServerSyncException("尚未配置服务器地址")
        val connection = (URL("$base$path").openConnection() as HttpURLConnection)
        if (SyncSettings.config.value.serverAllowSelfSigned && connection is HttpsURLConnection) {
            connection.sslSocketFactory = TrustAll.factory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        return try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "FreeNote-Android/1.0")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (code >= 400) throw ServerSyncException(parseError(text, code), code)
            if (text.isBlank()) throw ServerSyncException("服务器返回了空数据", code)
            try {
                JSONObject(text)
            } catch (e: Exception) {
                throw ServerSyncException("服务器返回了无效数据", code)
            }
        } catch (e: ServerSyncException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw ServerSyncException("连接服务器超时")
        } catch (e: SSLException) {
            val hint = if (SyncSettings.config.value.serverAllowSelfSigned) {
                ""
            } else {
                "：自签名证书可在同步设置中开启「允许自签名证书」"
            }
            throw ServerSyncException("HTTPS 连接失败$hint")
        } catch (e: IOException) {
            throw ServerSyncException("无法连接服务器：${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseError(text: String, code: Int): String {
        return try {
            val payload = JSONObject(text)
            when {
                !payload.isNull("detail") -> {
                    val detail = payload.opt("detail")
                    if (detail is JSONArray) "请求参数无效" else detail?.toString() ?: "HTTP $code"
                }
                !payload.isNull("error") -> payload.optString("error")
                !payload.isNull("message") -> payload.optString("message")
                else -> "HTTP $code"
            }
        } catch (e: Exception) {
            "HTTP $code"
        }
    }

    // ------------------------------------------------------------------ 账户

    suspend fun testConnection(serverUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val payload = request("GET", serverUrl, "/api/health", timeoutMs = 8_000)
            var message = "连接正常（服务端 v${payload.optString("version", "?")}）"
            if (ServerUrl.needsHttpsHint(serverUrl)) {
                message += "；当前为明文 HTTP，公网使用请配置 HTTPS"
            }
            true to message
        } catch (e: ServerSyncException) {
            false to e.message.orEmpty()
        }
    }

    suspend fun register(serverUrl: String, username: String, password: String): ServerAuthResult =
        withContext(Dispatchers.IO) {
            try {
                val payload = request(
                    "POST", serverUrl, "/api/register",
                    JSONObject().put("username", username).put("password", password)
                )
                ServerAuthResult(
                    ok = true,
                    message = "注册成功，已登录为 $username",
                    token = payload.optString("token"),
                    username = payload.optString("username", username)
                )
            } catch (e: ServerSyncException) {
                ServerAuthResult(false, e.message.orEmpty())
            }
        }

    suspend fun login(serverUrl: String, username: String, password: String): ServerAuthResult =
        withContext(Dispatchers.IO) {
            try {
                val payload = request(
                    "POST", serverUrl, "/api/login",
                    JSONObject().put("username", username).put("password", password)
                )
                ServerAuthResult(
                    ok = true,
                    message = "登录成功，欢迎 $username",
                    token = payload.optString("token"),
                    username = payload.optString("username", username)
                )
            } catch (e: ServerSyncException) {
                ServerAuthResult(false, e.message.orEmpty())
            }
        }

    suspend fun logout(serverUrl: String, token: String) {
        withContext(Dispatchers.IO) {
            if (serverUrl.isBlank() || token.isBlank()) return@withContext
            try {
                request("POST", serverUrl, "/api/logout", JSONObject(), token)
            } catch (e: Exception) {
                Log.d(TAG, "logout failed: ${e.message}")
            }
        }
    }

    suspend fun changePassword(
        serverUrl: String,
        token: String,
        oldPassword: String,
        newPassword: String
    ): ServerAuthResult = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank() || token.isBlank()) {
            return@withContext ServerAuthResult(false, "请先登录服务器账号")
        }
        try {
            val payload = request(
                "POST", serverUrl, "/api/change_password",
                JSONObject().put("oldPassword", oldPassword).put("newPassword", newPassword),
                token
            )
            val revoked = payload.optInt("revokedTokens", 0)
            val message = if (revoked > 0) {
                "密码已修改，已退出其他 $revoked 个设备，请在新设备上重新登录"
            } else {
                "密码已修改"
            }
            ServerAuthResult(true, message)
        } catch (e: ServerSyncException) {
            ServerAuthResult(false, e.message.orEmpty())
        }
    }

    suspend fun deleteAccount(
        serverUrl: String,
        token: String,
        password: String
    ): ServerAuthResult = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank() || token.isBlank()) {
            return@withContext ServerAuthResult(false, "请先登录服务器账号")
        }
        try {
            request(
                "POST", serverUrl, "/api/delete_account",
                JSONObject().put("password", password),
                token
            )
            ServerAuthResult(true, "账号已注销，服务端数据已删除（本机便签保留）")
        } catch (e: ServerSyncException) {
            ServerAuthResult(false, e.message.orEmpty())
        }
    }

    // ------------------------------------------------------------------ 同步

    /**
     * 增量位点/附件上传记录按「服务器 + 账号」隔离（与 Windows 端
     * `last_seq:<server>:<user>` 的语义一致）；换账号不会复用旧位点而漏拉数据。
     */
    private fun accountKey(prefix: String, serverUrl: String): String {
        val server = ServerUrl.normalize(serverUrl).trim()
        val username = SyncSettings.config.value.username.trim()
        return "$prefix:$server:$username"
    }

    private suspend fun loadUploadedNames(key: String): MutableSet<String> {
        val raw = store.metaGet(key) ?: return mutableSetOf()
        val array = try {
            org.json.JSONArray(raw)
        } catch (e: Exception) {
            return mutableSetOf()
        }
        val names = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val name = array.optString(index)
            if (name.isNotBlank()) names.add(name)
        }
        return names
    }

    private suspend fun saveUploadedNames(key: String, names: Set<String>) {
        store.metaSet(key, org.json.JSONArray(names.sorted()).toString())
    }

    suspend fun sync(serverUrl: String, token: String): ServerSyncResult = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank() || token.isBlank()) {
            return@withContext ServerSyncResult(false, "尚未登录，请先在同步设置中登录或注册")
        }
        val lastSeqKey = accountKey("last_seq", serverUrl)
        val uploadedKey = accountKey("attachments.uploaded", serverUrl)
        val sinceSeq = store.metaGet(lastSeqKey, "0")?.toLongOrNull() ?: 0L

        val outbox = store.outbox()
        val pushNotes = JSONArray()
        val tombstones = JSONArray()
        val pushedAt = mutableListOf<Pair<String, Long>>()
        for (entry in outbox) {
            val local = store.get(entry.noteId)
            if (local != null) {
                pushNotes.put(SyncProtocol.noteToJson(local))
                pushedAt.add(entry.noteId to local.updatedAt)
            } else if (entry.deleted) {
                tombstones.put(
                    JSONObject().put("id", entry.noteId).put("deletedAt", entry.queuedAt)
                )
            }
        }

        val uploaded = loadUploadedNames(uploadedKey)
        val uploadedAdded = mutableSetOf<String>()
        var uploadedReset = false
        val rejectedNames = mutableSetOf<String>()
        var applied = 0
        var conflicts = 0
        var imagesUp = 0
        var imagesDown = 0
        var attachmentsSupported = true
        var currentSeq = sinceSeq
        var firstRound = true
        val remoteVersions = HashMap<String, Long>()

        // 附件按批传输（每批 ≤8 个 / ≤8MB）：同一次同步内循环，直到无进展或无待办
        for (round in 0 until 8) {
            val requestBody = JSONObject()
                .put("sinceSeq", currentSeq)
                .put("notes", if (firstRound) pushNotes else JSONArray())
                .put("tombstones", if (firstRound) tombstones else JSONArray())
            val wantBatch = JSONArray()
            val uploadPayload = JSONArray()
            if (attachmentsSupported) {
                val (referenced, present) = attachments.inventory()
                val missing = (referenced - present).sorted()
                missing.take(AttachmentStore.ATTACHMENTS_PER_MESSAGE)
                    .forEach { wantBatch.put(it) }
                val candidates = ((referenced - uploaded) intersect present).sorted()
                for (name in attachments.takeBatch(candidates)) {
                    val data = attachments.blobData(name) ?: continue
                    uploadPayload.put(
                        JSONObject().put("name", name)
                            .put("data", attachments.encodeWire(data))
                    )
                }
            }
            requestBody.put("wantAttachments", wantBatch).put("attachments", uploadPayload)

            var payload = try {
                request("POST", serverUrl, "/api/sync", requestBody, token)
            } catch (e: ServerSyncException) {
                if (e.statusCode == 401) {
                    // 令牌失效：清掉本地登录态，避免自动同步反复失败
                    SyncSettings.setToken("")
                    return@withContext ServerSyncResult(
                        false, "登录已过期，请在同步设置中重新登录"
                    )
                }
                return@withContext ServerSyncResult(false, e.message.orEmpty())
            }
            firstRound = false

            if (!payload.optJSONArray("capabilities").containsString("attachments")) {
                // 旧版服务端不支持附件字段：停用本端附件逻辑，便签同步保持可用
                attachmentsSupported = false
            }

            var seq = payload.optLong("seq", currentSeq)
            if (seq < currentSeq) {
                // 服务端计数回退（换库/恢复备份）：改从 0 全量重拉；上传记录作废重传
                uploaded.clear()
                uploadedAdded.clear()
                uploadedReset = true
                payload = try {
                    request(
                        "POST", serverUrl, "/api/sync",
                        JSONObject().put("sinceSeq", 0)
                            .put("notes", JSONArray())
                            .put("tombstones", JSONArray()),
                        token
                    )
                } catch (e: ServerSyncException) {
                    return@withContext ServerSyncResult(false, e.message.orEmpty())
                }
                seq = payload.optLong("seq", 0L)
            } else if (seq == 0L) {
                seq = currentSeq
            }
            currentSeq = seq

            var roundApplied = 0
            val remoteNotes = payload.optJSONArray("notes") ?: JSONArray()
            for (index in 0 until remoteNotes.length()) {
                val item = remoteNotes.optJSONObject(index) ?: continue
                val remote = try {
                    SyncProtocol.noteFromJson(item)
                } catch (e: Exception) {
                    continue
                }
                if (remote.id.isBlank()) continue
                remoteVersions[remote.id] = remote.updatedAt
                val local = store.get(remote.id)
                when {
                    local == null -> {
                        store.applyRemote(remote)
                        applied++
                        roundApplied++
                    }
                    remote.updatedAt > local.updatedAt -> {
                        store.applyRemote(remote)
                        applied++
                        roundApplied++
                    }
                    remote.updatedAt == local.updatedAt -> {
                        // 同毫秒平局：删除优先；同删除态时内容哈希小者胜（与服务端/P2P 一致）
                        if (remote.isDeleted != local.isDeleted) {
                            if (remote.isDeleted) {
                                store.applyRemote(remote)
                                applied++
                                roundApplied++
                            }
                        } else {
                            val remoteHash = SyncProtocol.noteContentHash(remote)
                            val localHash = SyncProtocol.noteContentHash(local)
                            if (remoteHash.isNotEmpty() && remoteHash < localHash) {
                                store.applyRemote(remote)
                                applied++
                                roundApplied++
                            }
                        }
                    }
                    else -> {
                        // 本地更新：重新入队，下轮推送时服务端按 LWW 接受
                        store.enqueue(local.id, local.isDeleted)
                        conflicts++
                    }
                }
            }

            var roundProgress = roundApplied > 0
            if (attachmentsSupported) {
                val downloads = payload.optJSONArray("attachments") ?: JSONArray()
                for (index in 0 until downloads.length()) {
                    val item = downloads.optJSONObject(index) ?: continue
                    val name = item.optString("name")
                    val data = attachments.decodeWire(item.optString("data"))
                    if (data != null && attachments.storeBlob(name, data)) {
                        imagesDown++
                        roundProgress = true
                    }
                }
                val storedNames = payload.optJSONArray("attachmentsStored") ?: JSONArray()
                for (index in 0 until storedNames.length()) {
                    val name = storedNames.optString(index)
                    if (name.isNotBlank() && uploaded.add(name)) {
                        uploadedAdded.add(name)
                        imagesUp++
                        roundProgress = true
                    }
                }
                val rejectedNamesJson = payload.optJSONArray("attachmentsRejected") ?: JSONArray()
                for (index in 0 until rejectedNamesJson.length()) {
                    val name = rejectedNamesJson.optString(index)
                    if (name.isNotBlank()) rejectedNames.add(name)
                }
            }

            if (!attachmentsSupported || !roundProgress) break
            val (referenced, present) = attachments.inventory()
            if ((referenced - present).isEmpty() &&
                ((referenced - uploaded) intersect present).isEmpty()
            ) {
                break
            }
        }

        store.outboxClear(outbox)
        // 同步期间本地又被修改的便签：重新入队，下一轮再推送
        for ((noteId, pushedTimestamp) in pushedAt) {
            val local = store.get(noteId) ?: continue
            val remoteVersion = remoteVersions[noteId] ?: 0L
            if (local.updatedAt > maxOf(pushedTimestamp, remoteVersion)) {
                store.enqueue(noteId, local.isDeleted)
            }
        }
        store.metaSet(lastSeqKey, currentSeq.toString())
        store.metaSet("last_sync_at", System.currentTimeMillis().toString())
        if (uploadedAdded.isNotEmpty() || uploadedReset) {
            // 服务端计数回退后即使本轮没传成功也要落盘：避免下次同步误以为附件已上传
            saveUploadedNames(uploadedKey, uploaded)
        }

        val pending = if (attachmentsSupported) {
            runCatching { attachments.missingNames().size }.getOrDefault(0)
        } else {
            0
        }
        var message = "服务器同步完成：推送 ${pushNotes.length()} 条，拉取 $applied 条"
        if (imagesUp > 0 || imagesDown > 0) {
            message += "，图片 上传 $imagesUp、下载 $imagesDown"
        }
        if (rejectedNames.isNotEmpty()) {
            message += "，${rejectedNames.size} 张图片被服务器拒绝（空间不足等）"
        }
        if (pending > 0) message += "，$pending 张图片待续传"
        if (conflicts > 0) message += "，$conflicts 条本地新改动待下一轮推送"
        return@withContext ServerSyncResult(
            ok = true,
            message = message,
            pulled = applied,
            imagesUploaded = imagesUp,
            imagesDownloaded = imagesDown,
            imagesPending = pending
        )
    }

    companion object {
        private const val TAG = "ServerSyncClient"
    }
}

/** JSONArray? 是否包含指定字符串（capabilities 探测用）。 */
private fun JSONArray?.containsString(value: String): Boolean {
    if (this == null) return false
    for (index in 0 until length()) {
        if (optString(index) == value) return true
    }
    return false
}
