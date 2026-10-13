package com.zqr.freenote.data.sync

import com.zqr.freenote.data.AttachmentStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

open class P2PSyncException(message: String) : Exception(message)

class P2PAuthException(message: String) : P2PSyncException(message)

/** 同步账户不一致（服务器账号或离线账户不同）。 */
class P2PAccountException(message: String) : P2PSyncException(message)

open class P2PProtocolException(message: String) : P2PSyncException(message)

class P2PSessionExpiredException(message: String) : P2PProtocolException(message)

data class P2PExchangeResult(
    val ok: Boolean = true,
    val message: String = "",
    val upToDate: Boolean = false,
    val pushed: Int = 0,
    val pulled: Int = 0,
    val conflicts: Int = 0,
    val peerDigest: String = "",
    val imagesPushed: Int = 0,
    val imagesPulled: Int = 0,
    val imagesPending: Int = 0
)

/**
 * P2P 同步客户端（与 FreeNote p2p/client.py 协议一致）：
 * 两步交换：manifest（交换状态清单）→ exchange（互通增量），双方 LWW 合并；
 * 协议 v6：exchange 后按清单补传附件（image_put / image_get 两个端点）。
 */
class P2PClient(
    private val store: SyncStore,
    private val attachments: AttachmentStore,
    private val keyProvider: () -> ByteArray?,
    private val deviceId: String,
    private val deviceName: String,
    private val listenPort: Int = 0,
    private val accountFingerprint: String = ""
) {

    private companion object {
        const val TIMEOUT_MS = 20_000
        const val PING_PATH = "/api/freenote/ping"
        const val MANIFEST_PATH = "/api/freenote/manifest"
        const val EXCHANGE_PATH = "/api/freenote/exchange"
        const val IMAGE_GET_PATH = "/api/freenote/image_get"
        const val IMAGE_PUT_PATH = "/api/freenote/image_put"

        /**
         * 已见过的响应随机数：同一有效响应被重放（例如旧「已是最新」）时直接拒绝。
         * 进程级共享（companion），跨 P2PClient 实例也生效。
         */
        private val responseNonces = java.util.concurrent.ConcurrentHashMap<String, Long>()

        private fun acceptResponseNonce(nonce: String, now: Long): Boolean {
            if (responseNonces.size > 4096) {
                responseNonces.entries.removeIf { it.value <= now }
            }
            return responseNonces.putIfAbsent(nonce, now + SyncProtocol.AUTH_CLOCK_SKEW_MS) == null
        }
    }

    private fun key(): ByteArray =
        keyProvider()?.takeIf { it.isNotEmpty() } ?: throw P2PSyncException("尚未设置同步口令")

    // ------------------------------------------------------------------ HTTP

    suspend fun ping(address: String, port: Int): JSONObject = withContext(Dispatchers.IO) {
        val connection = try {
            URL("http://$address:$port$PING_PATH").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw P2PSyncException("设备地址无效：$address")
        }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            val code = connection.responseCode
            val bytes = (if (code >= 400) connection.errorStream else connection.inputStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            if (code >= 400) throw P2PSyncException(errorMessage(bytes, code))
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: P2PSyncException) {
            throw e
        } catch (e: IOException) {
            throw P2PSyncException("无法连接对端：${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    private fun post(address: String, port: Int, path: String, payload: JSONObject): JSONObject {
        val key = key()
        val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
        val body = SyncProtocol.seal(key, plaintext)
        val timestamp = System.currentTimeMillis()
        val nonce = SyncProtocol.newNonce()
        val connection = try {
            URL("http://$address:$port$path").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw P2PSyncException("设备地址无效：$address")
        }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty(SyncProtocol.ENVELOPE_HEADER, SyncProtocol.ENVELOPE_MODE)
            connection.setRequestProperty("X-FreeNote-Device", deviceId)
            connection.setRequestProperty("X-FreeNote-Ts", timestamp.toString())
            connection.setRequestProperty("X-FreeNote-Nonce", nonce)
            if (accountFingerprint.isNotEmpty()) {
                connection.setRequestProperty("X-FreeNote-Account", accountFingerprint)
            }
            connection.setRequestProperty(
                "X-FreeNote-Sig",
                SyncProtocol.sign(key, "POST", path, timestamp, nonce, body)
            )
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val raw = (if (code >= 400) connection.errorStream else connection.inputStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            when {
                code == 401 -> throw P2PAuthException("同步口令不一致，已拒绝同步")
                code == 403 && errorCode(raw) == "account-mismatch" ->
                    throw P2PAccountException(errorMessage(raw, code))
                code == 409 -> throw P2PSessionExpiredException("同步会话已过期")
                code == 426 -> throw P2PProtocolException(errorMessage(raw, code))
                code >= 400 -> throw P2PSyncException(errorMessage(raw, code))
            }
            verifyResponse(path, connection, raw, key)
            val plain = SyncProtocol.unseal(key, raw)
                ?: throw P2PProtocolException("对端响应解密失败")
            return JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: P2PSyncException) {
            throw e
        } catch (e: IOException) {
            throw P2PSyncException("无法连接对端：${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyResponse(
        path: String,
        connection: HttpURLConnection,
        raw: ByteArray,
        key: ByteArray
    ) {
        val signature = connection.getHeaderField("X-FreeNote-Sig")
            ?: throw P2PProtocolException("对端响应缺少签名")
        val timestamp = connection.getHeaderField("X-FreeNote-Ts")?.toLongOrNull()
            ?: throw P2PProtocolException("对端响应签名无效")
        val nonce = connection.getHeaderField("X-FreeNote-Nonce").orEmpty()
        if (!SyncProtocol.verifySignature(key, "RESPONSE", path, timestamp, nonce, raw, signature)) {
            throw P2PProtocolException("对端响应签名校验失败")
        }
        // 拒绝重放旧响应（例如把过去的「已是最新」重新放给客户端）
        val now = System.currentTimeMillis()
        if (abs(now - timestamp) > SyncProtocol.AUTH_CLOCK_SKEW_MS) {
            throw P2PProtocolException("对端响应时间戳无效")
        }
        if (!acceptResponseNonce(nonce, now)) {
            throw P2PProtocolException("对端响应重复（疑似重放），已拒绝")
        }
    }

    private fun errorMessage(raw: ByteArray, code: Int): String = try {
        val payload = JSONObject(String(raw, Charsets.UTF_8))
        payload.optString("error").ifBlank { payload.optString("detail").ifBlank { "HTTP $code" } }
    } catch (e: Exception) {
        "HTTP $code"
    }

    private fun errorCode(raw: ByteArray): String = try {
        JSONObject(String(raw, Charsets.UTF_8)).optString("code")
    } catch (e: Exception) {
        ""
    }

    // ------------------------------------------------------------------ 同步

    suspend fun sync(address: String, port: Int): P2PExchangeResult = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (attempt in 1..2) {
            val manifestPayload = JSONObject()
                .put("protocol", SyncProtocol.PROTOCOL_VERSION)
                .put("device", SyncProtocol.deviceJson(deviceId, deviceName, listenPort))
                .put("digest", store.stateDigest())
                .put("entries", SyncProtocol.entriesPayload(store.syncEntries()))
                .put(
                    "images",
                    JSONArray(attachments.localNames().sorted().take(AttachmentStore.INVENTORY_LIMIT))
                )
            val manifest = post(address, port, MANIFEST_PATH, manifestPayload)
            val peerDevice = manifest.optJSONObject("device") ?: JSONObject()
            val peerId = peerDevice.optString("id")
            if (peerId.isNotBlank() && peerId == deviceId) {
                throw P2PSyncException("检测到与自身设备 ID 相同的对端（请检查是否重复导入数据）")
            }
            if (manifest.optBoolean("upToDate")) {
                store.materializeAllTombstones()
                return@withContext P2PExchangeResult(
                    ok = true,
                    message = "已是最新",
                    upToDate = true,
                    peerDigest = manifest.optString("digest")
                )
            }

            val sessionId = manifest.optString("sessionId")
            if (sessionId.isBlank()) throw P2PProtocolException("对端未返回同步会话")
            val need = mutableListOf<String>()
            manifest.optJSONArray("need")?.let { array ->
                for (index in 0 until array.length()) {
                    val value = array.optString(index)
                    if (value.isNotBlank()) need.add(value)
                }
            }
            val notesOut = SyncProtocol.collectNotes(store, need)
            // 附件（协议 v6）：wantFromPeer = 本机引用但缺失、对端清单持有的；
            // pushNames = 对端清单声明想要、需要本机推送的
            val wantFromPeer = wantedNames(manifest.optJSONArray("images"))
            val pushNames = attachments.validNameList(manifest.optJSONArray("wantImages"))

            val exchange = try {
                post(
                    address, port, EXCHANGE_PATH,
                    JSONObject()
                        .put("protocol", SyncProtocol.PROTOCOL_VERSION)
                        .put("device", SyncProtocol.deviceJson(deviceId, deviceName, listenPort))
                        .put("sessionId", sessionId)
                        .put("notes", notesOut)
                )
            } catch (e: P2PSessionExpiredException) {
                lastError = e
                if (attempt == 1) continue
                throw e
            }
            val result = applyExchange(exchange, notesOut.length())
            val (imagesPushed, imagesPulled) = try {
                transferImages(address, port, pushNames, wantFromPeer)
            } catch (e: P2PSyncException) {
                // 便签同步已完成；附件通道故障不应回滚整体结果
                0 to 0
            }
            val pending = runCatching { attachments.missingNames().size }.getOrDefault(0)
            val parts = mutableListOf<String>()
            if (imagesPushed > 0 || imagesPulled > 0) {
                parts.add("图片 送出 $imagesPushed、取回 $imagesPulled")
            }
            if (pending > 0) parts.add("$pending 张图片待续传")
            val suffix = if (parts.isEmpty()) "" else "（" + parts.joinToString("，") + "）"
            return@withContext result.copy(
                message = result.message + suffix,
                imagesPushed = imagesPushed,
                imagesPulled = imagesPulled,
                imagesPending = pending
            )
        }
        throw (lastError ?: P2PProtocolException("同步会话反复过期，请重试"))
    }

    /** 本机引用了、本地还没有、且对端清单声称拥有的附件名。 */
    private suspend fun wantedNames(peerInventory: JSONArray?): List<String> {
        val peer = attachments.validNameList(peerInventory).toHashSet()
        if (peer.isEmpty()) return emptyList()
        val (referenced, present) = attachments.inventory()
        return (referenced - present).filter { it in peer }.sorted()
    }

    /**
     * 交换附件：先推送对端要的（image_put），再拉取本机缺的（image_get）。
     * 每轮调用最多 8 个批次；剩余量由 imagesPending 报告，由同步总控决定是否续跑。
     */
    private fun transferImages(
        address: String,
        port: Int,
        pushNames: List<String>,
        wantNames: List<String>
    ): Pair<Int, Int> {
        var pushed = 0
        var pulled = 0
        val device = SyncProtocol.deviceJson(deviceId, deviceName, listenPort)

        var remaining = pushNames
        for (round in 0 until 8) {
            if (remaining.isEmpty()) break
            val batch = attachments.takeBatch(remaining)
            if (batch.isEmpty()) break
            val payload = JSONArray()
            batch.forEach { name ->
                val data = attachments.blobData(name) ?: return@forEach
                payload.put(
                    JSONObject().put("name", name).put("data", attachments.encodeWire(data))
                )
            }
            if (payload.length() == 0) break
            val response = post(
                address, port, IMAGE_PUT_PATH,
                JSONObject()
                    .put("protocol", SyncProtocol.PROTOCOL_VERSION)
                    .put("device", device)
                    .put("images", payload)
            )
            val stored = response.optJSONArray("stored") ?: JSONArray()
            pushed += stored.length()
            val sent = batch.toSet()
            remaining = remaining.filterNot { it in sent }
            if (stored.length() < payload.length()) break // 对端拒收：停止本轮推送
        }

        var remainingWant = wantNames
        for (round in 0 until 8) {
            if (remainingWant.isEmpty()) break
            val batch = remainingWant.take(AttachmentStore.ATTACHMENTS_PER_MESSAGE)
            val response = post(
                address, port, IMAGE_GET_PATH,
                JSONObject()
                    .put("protocol", SyncProtocol.PROTOCOL_VERSION)
                    .put("device", device)
                    .put("names", JSONArray(batch))
            )
            var got = 0
            val received = HashSet<String>()
            val images = response.optJSONArray("images") ?: JSONArray()
            for (index in 0 until images.length()) {
                val item = images.optJSONObject(index) ?: continue
                val name = item.optString("name")
                val data = attachments.decodeWire(item.optString("data"))
                if (data != null && attachments.storeBlob(name, data)) {
                    got++
                    received.add(name)
                }
            }
            pulled += got
            // 对端明确没有的名（missing）不再要；因字节上限未返回的名保留到下一轮
            val missing = HashSet<String>()
            response.optJSONArray("missing")?.let { array ->
                for (index in 0 until array.length()) {
                    val name = array.optString(index)
                    if (name.isNotBlank()) missing.add(name)
                }
            }
            remainingWant = remainingWant.filterNot { it in received || it in missing }
            if (got == 0) break // 无进展：等待下一轮
        }
        return pushed to pulled
    }

    private suspend fun applyExchange(
        exchange: JSONObject,
        pushed: Int
    ): P2PExchangeResult {
        var pulled = 0
        val notes = exchange.optJSONArray("notes") ?: JSONArray()
        for (index in 0 until notes.length()) {
            val item = notes.optJSONObject(index) ?: continue
            val note = try {
                SyncProtocol.noteFromJson(item)
            } catch (e: Exception) {
                continue
            }
            if (note.id.isNotBlank() && store.applyRemoteLww(note)) pulled++
        }
        // 已传播的墓碑物化为删除行，保证摘要收敛
        store.materializeAllTombstones()

        val conflicts = exchange.optInt("conflicts")
        val parts = mutableListOf<String>()
        if (pushed > 0) parts.add("送出 $pushed 条")
        if (pulled > 0) parts.add("取回 $pulled 条")
        if (conflicts > 0) parts.add("$conflicts 条冲突保留较新版本")
        val message = if (parts.isEmpty()) "同步完成" else "同步完成：" + parts.joinToString("，")
        return P2PExchangeResult(
            ok = true,
            message = message,
            pushed = pushed,
            pulled = pulled,
            conflicts = conflicts,
            peerDigest = exchange.optString("digest")
        )
    }
}
