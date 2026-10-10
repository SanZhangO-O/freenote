package com.zqr.freenote.data.sync

import android.util.Log
import com.zqr.freenote.data.AttachmentStore
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置 P2P 同步服务（与 FreeNote p2p/server.py 协议一致）：
 * Windows 端可以像发现另一台 FreeNote 一样发现 Android 设备并主动同步。
 *
 * 端点：
 * - GET  /api/freenote/ping     健康检查（无需认证，返回协议版本与账户指纹）
 * - POST /api/freenote/manifest 交换状态清单
 * - POST /api/freenote/exchange 互通增量
 *
 * 所有 POST 都要求 HMAC 签名；账户指纹不一致或未设置同步口令一律拒绝，
 * 避免同一局域网内不同账户（用户）的数据互相串。
 */
class P2PServer(
    private val store: SyncStore,
    private val attachments: AttachmentStore,
    private val deviceId: String,
    private val deviceName: String,
    private val keyProvider: () -> ByteArray?,
    private val accountFingerprintProvider: () -> String = { "" },
    private val onRemoteSync: (
        deviceId: String,
        deviceName: String,
        address: String,
        port: Int,
        applied: Int,
        conflicts: Int
    ) -> Unit,
    private val preferredPort: Int = DEFAULT_SYNC_PORT
) {

    private data class Session(
        val manifest: Map<String, SyncProtocol.ManifestEntry>,
        val createdAt: Long
    )

    private companion object {
        const val TAG = "P2PServer"
        const val PING_PATH = "/api/freenote/ping"
        const val MANIFEST_PATH = "/api/freenote/manifest"
        const val EXCHANGE_PATH = "/api/freenote/exchange"
        const val IMAGE_GET_PATH = "/api/freenote/image_get"
        const val IMAGE_PUT_PATH = "/api/freenote/image_put"
        const val SESSION_TTL_MS = 120_000L
        const val MAX_BODY = 32 * 1024 * 1024
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val nonces = ConcurrentHashMap<String, Long>()
    @Volatile
    private var executor: ExecutorService = Executors.newCachedThreadPool()

    // 置位后拒绝新会话登记，防止 stop() 清理后被在途 handler 重新插入残留
    @Volatile
    private var stopped = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    var port: Int = 0
        private set

    val running: Boolean get() = serverSocket != null

    // ------------------------------------------------------------------ 生命周期

    fun start(): Boolean {
        if (serverSocket != null) return true
        val bound = bindSocket() ?: return false
        stopped = false
        if (executor.isShutdown) executor = Executors.newCachedThreadPool()
        serverSocket = bound
        port = bound.localPort
        acceptThread = Thread({ acceptLoop(bound) }, "freenote-p2p-server").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun bindSocket(): ServerSocket? {
        val candidates = if (preferredPort > 0) {
            buildList {
                add(preferredPort)
                for (offset in 1..10) add(preferredPort + offset)
                add(0)
            }
        } else {
            listOf(0)
        }
        for (candidate in candidates) {
            if (candidate > 65535) continue
            val socket = ServerSocket()
            try {
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(candidate))
                return socket
            } catch (e: IOException) {
                runCatching { socket.close() }
            }
        }
        return null
    }

    fun stop() {
        stopped = true
        val socket = serverSocket
        serverSocket = null
        runCatching { socket?.close() }
        acceptThread = null
        sessions.clear()
        nonces.clear()
        executor.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            // 与 stop() 的 shutdownNow 竞争时 execute 会被拒绝，此时需补关连接防 fd 泄漏
            runCatching {
                executor.execute {
                    try {
                        runBlocking { handleConnection(client) }
                    } catch (e: Exception) {
                        Log.d(TAG, "connection error: ${e.message}")
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }.onFailure {
                runCatching { client.close() }
            }
        }
    }

    // ------------------------------------------------------------------ HTTP

    private suspend fun handleConnection(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            respond(output, 400, JSONObject().put("error", "请求格式无效"), null, "")
            return
        }
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index > 0) headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body: ByteArray = if (contentLength > 0) {
            if (contentLength > MAX_BODY) {
                respond(output, 400, JSONObject().put("error", "请求体过大"), null, path)
                return
            }
            val bytes = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(bytes, offset, contentLength - offset)
                if (read <= 0) return
                offset += read
            }
            bytes
        } else {
            ByteArray(0)
        }

        when {
            method == "GET" && path == PING_PATH -> respond(
                output,
                200,
                JSONObject().put("app", "freenote").put("version", 1)
                    .put("protocol", SyncProtocol.PROTOCOL_VERSION)
                    .put("account", accountFingerprint()),
                null,
                path
            )
            method == "POST" && (path == MANIFEST_PATH || path == EXCHANGE_PATH ||
                path == IMAGE_GET_PATH || path == IMAGE_PUT_PATH) ->
                handleSigned(path, body, headers, output, client.inetAddress?.hostAddress.orEmpty())
            else -> respond(output, 404, JSONObject().put("error", "not found"), null, path)
        }
    }

    private suspend fun handleSigned(
        path: String,
        body: ByteArray,
        headers: Map<String, String>,
        output: OutputStream,
        clientAddress: String
    ) {
        val key = keyProvider()?.takeIf { it.isNotEmpty() }
        if (key == null) {
            respond(output, 403, JSONObject().put("error", "对方未设置同步口令"), null, path)
            return
        }
        val peerAccount = headers["x-freenote-account"].orEmpty()
        if (peerAccount != accountFingerprint()) {
            // 同一局域网内的不同账户禁止互通；错误响应不带密钥，对端可直接读出原因
            respond(
                output,
                403,
                JSONObject()
                    .put("error", "双方同步账户不一致：请确认两台设备登录了同一服务器账号，或设置相同的离线账户")
                    .put("code", "account-mismatch"),
                null,
                path
            )
            return
        }
        val timestamp = headers["x-freenote-ts"]?.toLongOrNull() ?: 0L
        val nonce = headers["x-freenote-nonce"].orEmpty()
        val signature = headers["x-freenote-sig"].orEmpty()
        val now = System.currentTimeMillis()
        val fresh = abs(now - timestamp) <= SyncProtocol.AUTH_CLOCK_SKEW_MS
        val validNonce = nonce.length in 8..64
        val validSignature = SyncProtocol.verifySignature(key, "POST", path, timestamp, nonce, body, signature)
        if (!fresh || !validNonce || !validSignature || !acceptNonce(nonce, now)) {
            respond(
                output,
                401,
                JSONObject().put("error", "认证失败：同步口令不一致或请求被篡改"),
                null,
                path
            )
            return
        }

        val plainBody = SyncProtocol.unseal(key, body)
        if (plainBody == null) {
            respond(
                output,
                400,
                JSONObject().put("error", "请求未加密或解密失败，请升级客户端"),
                null,
                path
            )
            return
        }
        val payload = try {
            JSONObject(String(plainBody, Charsets.UTF_8))
        } catch (e: Exception) {
            respond(output, 400, JSONObject().put("error", "请求不是有效的 JSON"), null, path)
            return
        }
        if (payload.optInt("protocol") != SyncProtocol.PROTOCOL_VERSION) {
            // 426 Upgrade Required：错误响应不带密钥（与 FreeNote 一致），对端可直接读出原因
            respond(output, 426, JSONObject().put("error", "协议版本不一致，请升级客户端"), null, path)
            return
        }
        try {
            when (path) {
                MANIFEST_PATH -> handleManifest(payload, output, key, path, clientAddress)
                EXCHANGE_PATH -> handleExchange(payload, output, key, path, clientAddress)
                IMAGE_GET_PATH -> handleImageGet(payload, output, key, path)
                IMAGE_PUT_PATH -> handleImagePut(payload, output, key, path)
                else -> respond(output, 404, JSONObject().put("error", "not found"), null, path)
            }
        } catch (e: Exception) {
            Log.d(TAG, "handle error: ${e.message}")
            respond(output, 500, JSONObject().put("error", "对方内部错误"), null, path)
        }
    }

    // ------------------------------------------------------------------ 业务

    private suspend fun handleManifest(
        payload: JSONObject,
        output: OutputStream,
        key: ByteArray,
        path: String,
        clientAddress: String
    ) {
        val device = payload.optJSONObject("device") ?: JSONObject()
        val peerId = device.optString("id")
        val peerName = device.optString("name")
        val peerPort = device.optInt("port")
        val peerManifest = SyncProtocol.manifestFromEntries(payload.optJSONArray("entries"))

        val localDigest = store.stateDigest()
        val remoteDigest = payload.optString("digest")
        val upToDate = remoteDigest.isNotEmpty() && remoteDigest == localDigest
        val response = JSONObject()
            .put("protocol", SyncProtocol.PROTOCOL_VERSION)
            .put("device", SyncProtocol.deviceJson(deviceId, deviceName, 0))
            .put("digest", localDigest)
            .put("upToDate", upToDate)
        if (!upToDate) {
            // 附件清单（协议 v6）：交换本机持有的附件名；
            // wantImages = 本机引用但缺失、且对方清单持有的附件（由对方在交换后推送）
            val peerImageNames =
                attachments.validNameList(payload.optJSONArray("images")).toHashSet()
            response.put(
                "images",
                JSONArray(attachments.localNames().sorted().take(AttachmentStore.INVENTORY_LIMIT))
            )
            val wantImages = if (peerImageNames.isEmpty()) {
                emptyList()
            } else {
                val (referenced, present) = attachments.inventory()
                (referenced - present).filter { it in peerImageNames }.sorted()
            }
            response.put("wantImages", JSONArray(wantImages))
            val local = store.syncEntries().associate {
                it.id to SyncProtocol.ManifestEntry(it.updatedAt, it.isDeleted, it.contentHash)
            }
            val need = JSONArray()
            for ((id, incoming) in peerManifest) {
                if (SyncProtocol.incomingWins(local[id], incoming)) need.put(id)
            }
            purgeSessions()
            if (!stopped) {
                val sessionId = UUID.randomUUID().toString().replace("-", "")
                sessions[sessionId] = Session(peerManifest, System.currentTimeMillis())
                response.put("sessionId", sessionId).put("need", need)
            }
        }
        notifySync(peerId, peerName, clientAddress, peerPort, 0, 0)
        respond(output, 200, response, key, path)
    }

    private suspend fun handleExchange(
        payload: JSONObject,
        output: OutputStream,
        key: ByteArray,
        path: String,
        clientAddress: String
    ) {
        val device = payload.optJSONObject("device") ?: JSONObject()
        val peerId = device.optString("id")
        val peerName = device.optString("name")
        val peerPort = device.optInt("port")
        val sessionId = payload.optString("sessionId")
        val session = if (sessionId.isBlank()) null else sessions.remove(sessionId)
        if (session == null) {
            respond(output, 409, JSONObject().put("error", "同步会话已过期，请重试"), null, path)
            return
        }

        var applied = 0
        var conflicts = 0
        val notesIn = payload.optJSONArray("notes") ?: JSONArray()
        for (index in 0 until notesIn.length()) {
            val item = notesIn.optJSONObject(index) ?: continue
            val note = try {
                SyncProtocol.noteFromJson(item)
            } catch (e: Exception) {
                continue
            }
            if (note.id.isBlank()) continue
            if (store.applyRemoteLww(note)) applied++ else conflicts++
        }

        val out = SyncProtocol.notesPeerLacks(store, session.manifest)
        val response = JSONObject()
            .put("protocol", SyncProtocol.PROTOCOL_VERSION)
            .put("device", SyncProtocol.deviceJson(deviceId, deviceName, 0))
            .put("digest", store.stateDigest())
            .put("applied", applied)
            .put("conflicts", conflicts)
            .put("notes", out)
        notifySync(peerId, peerName, clientAddress, peerPort, applied, conflicts)
        respond(output, 200, response, key, path)
    }

    /** 按名取附件：返回请求列表中本机持有的附件（数量/字节双上限）。 */
    private fun handleImageGet(
        payload: JSONObject,
        output: OutputStream,
        key: ByteArray,
        path: String
    ) {
        val names = attachments.validNameList(payload.optJSONArray("names"))
        val images = JSONArray()
        val missing = JSONArray()
        var total = 0L
        var more = false
        for (name in names) {
            val data = attachments.blobData(name)
            if (data == null) {
                missing.put(name)
                continue
            }
            if (images.length() >= AttachmentStore.ATTACHMENTS_PER_MESSAGE ||
                total + data.size > AttachmentStore.ATTACHMENT_BYTES_PER_MESSAGE
            ) {
                more = true
                break // 上限之后的名称本轮不处理，由 more 标记提示客户端下轮继续
            }
            images.put(JSONObject().put("name", name).put("data", attachments.encodeWire(data)))
            total += data.size
        }
        respond(
            output, 200,
            JSONObject()
                .put("protocol", SyncProtocol.PROTOCOL_VERSION)
                .put("images", images)
                .put("missing", missing)
                .put("more", more),
            key, path
        )
    }

    /** 收附件：写入本机图片目录（名称必须与内容哈希一致，数量/字节双上限）。 */
    private fun handleImagePut(
        payload: JSONObject,
        output: OutputStream,
        key: ByteArray,
        path: String
    ) {
        val stored = JSONArray()
        val rejected = JSONArray()
        var total = 0L
        val imagesIn = payload.optJSONArray("images")
        if (imagesIn != null) {
            val limit = minOf(imagesIn.length(), AttachmentStore.ATTACHMENTS_PER_MESSAGE * 2)
            for (index in 0 until limit) {
                val item = imagesIn.optJSONObject(index) ?: continue
                val name = item.optString("name")
                val data = attachments.decodeWire(item.optString("data"))
                if (data == null ||
                    stored.length() >= AttachmentStore.ATTACHMENTS_PER_MESSAGE ||
                    total + data.size > AttachmentStore.ATTACHMENT_BYTES_PER_MESSAGE
                ) {
                    rejected.put(if (name.isBlank()) "?" else name)
                    continue
                }
                if (attachments.storeBlob(name, data)) {
                    stored.put(name)
                    total += data.size
                } else {
                    rejected.put(if (name.isBlank()) "?" else name)
                }
            }
        }
        respond(
            output, 200,
            JSONObject()
                .put("protocol", SyncProtocol.PROTOCOL_VERSION)
                .put("stored", stored)
                .put("rejected", rejected),
            key, path
        )
    }

    private fun notifySync(
        peerId: String,
        peerName: String,
        address: String,
        port: Int,
        applied: Int,
        conflicts: Int
    ) {
        if (peerId.isBlank()) return
        try {
            onRemoteSync(peerId, peerName, address, port, applied, conflicts)
        } catch (e: Exception) {
            Log.d(TAG, "onRemoteSync error: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 本机同步账户指纹（空串 = 未设置账户）。 */
    private fun accountFingerprint(): String = try {
        accountFingerprintProvider()
    } catch (e: Exception) {
        ""
    }

    /** 原子登记随机数：并发到达的同一 nonce（重放）只有一个能通过。 */
    private fun acceptNonce(nonce: String, now: Long): Boolean {
        if (nonces.size > 4096) purgeNonces(now)
        return nonces.putIfAbsent(nonce, now + SyncProtocol.AUTH_CLOCK_SKEW_MS) == null
    }

    private fun purgeNonces(now: Long) {
        nonces.entries.removeIf { it.value <= now }
    }

    private fun purgeSessions() {
        val cutoff = System.currentTimeMillis() - SESSION_TTL_MS
        sessions.entries.removeIf { it.value.createdAt < cutoff }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) {
                return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            }
            if (byte == '\n'.code) break
            if (byte != '\r'.code) buffer.write(byte)
            if (buffer.size() > 8192) return null
        }
        return buffer.toString("ISO-8859-1")
    }

    private fun respond(
        output: OutputStream,
        status: Int,
        payload: JSONObject,
        key: ByteArray?,
        path: String
    ) {
        val plain = payload.toString().toByteArray(Charsets.UTF_8)
        // 带密钥的业务响应（清单/便签）先加密再签名，签名覆盖密文；
        // 错误响应不带密钥，保持明文以便对端直接读出错误原因
        val body = if (key != null) SyncProtocol.seal(key, plain) else plain
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
        if (key != null) {
            head.append("Content-Type: application/octet-stream\r\n")
        } else {
            head.append("Content-Type: application/json; charset=utf-8\r\n")
        }
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Connection: close\r\n")
        if (key != null && path.isNotEmpty()) {
            val timestamp = System.currentTimeMillis()
            val nonce = SyncProtocol.newNonce()
            head.append(SyncProtocol.ENVELOPE_HEADER).append(": ")
                .append(SyncProtocol.ENVELOPE_MODE).append("\r\n")
            head.append("X-FreeNote-Ts: ").append(timestamp).append("\r\n")
            head.append("X-FreeNote-Nonce: ").append(nonce).append("\r\n")
            head.append("X-FreeNote-Sig: ")
                .append(SyncProtocol.sign(key, "RESPONSE", path, timestamp, nonce, body))
                .append("\r\n")
        }
        head.append("\r\n")
        try {
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            output.write(body)
            output.flush()
        } catch (e: IOException) {
            // 对端提前断开：忽略
        }
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        426 -> "Upgrade Required"
        500 -> "Internal Server Error"
        else -> "Error"
    }
}
