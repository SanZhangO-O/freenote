package com.zqr.freenote.data.sync

import com.zqr.freenote.data.Note
import com.zqr.freenote.data.NoteColor
import com.zqr.freenote.data.SyncEntry
import com.zqr.freenote.data.normalizeFolder
import com.zqr.freenote.data.normalizeRecurrence
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * P2P 协议核心（与 FreeNote p2p/protocol.py 字节级一致）：
 * - 同步账户（服务器账号或离线账户）+ 同步口令 → PBKDF2-HMAC-SHA256 32 字节组密钥
 *   （手写 PBKDF2，避免平台实现差异）；账户不同则密钥/发现 tag 不同，天然隔离；
 * - 请求/响应正文 AES-256-GCM 加密（密文 = nonce(12) + ciphertext + tag(16)）；
 * - 密文上再做 HMAC-SHA256 签名（时间戳 + 随机数 + 正文哈希），防篡改与重放；
 * - 状态摘要、LWW 判定、便签 JSON（camelCase，与 Android 备份格式一致）；
 * - 协议 v6：清单/摘要带内容哈希（含 folder 路径）与附件引用状态（freenote-img 附件），
 *   同毫秒平局按「删除优先 → 内容哈希小者胜」确定性仲裁。
 */
object SyncProtocol {
    const val PROTOCOL_VERSION = 6
    const val GROUP_KEY_ITERATIONS = 600_000
    const val AUTH_CLOCK_SKEW_MS = 5 * 60 * 1000L
    const val ENVELOPE_HEADER = "X-FreeNote-Enc"
    const val ENVELOPE_MODE = "aesgcm-v1"

    private const val ENVELOPE_NONCE_BYTES = 12
    private const val ENVELOPE_TAG_BYTES = 16
    private const val GCM_TAG_BITS = 128

    private val GROUP_SALT = "freenote-group-v1".toByteArray(Charsets.UTF_8)
    private val ANNOUNCE_CONTEXT = "freenote-announce-v1".toByteArray(Charsets.UTF_8)
    private val ACCOUNT_SALT_CONTEXT = "|freenote-account-v1|".toByteArray(Charsets.UTF_8)
    private val ACCOUNT_HASH_CONTEXT = "freenote-account-hash-v1\n".toByteArray(Charsets.UTF_8)

    // ------------------------------------------------------------------ 正文加密

    /** 用组密钥加密正文；每次使用新的随机 nonce（GCM 安全性要求）。 */
    fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(ENVELOPE_NONCE_BYTES)
        SecureRandom().nextBytes(nonce)
        return sealWithNonce(key, nonce, plaintext)
    }

    /**
     * 用指定 nonce 加密正文（与 FreeNote seal_with_nonce 对应）。
     * 生产路径始终走随机 nonce 的 [seal]；此入口用于跨语言测试向量校验字节级互通。
     */
    fun sealWithNonce(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    /** 解密正文；密钥不符或数据被篡改（GCM 校验失败）返回 null。 */
    fun unseal(key: ByteArray, blob: ByteArray): ByteArray? {
        if (blob.size <= ENVELOPE_NONCE_BYTES + ENVELOPE_TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, blob, 0, ENVELOPE_NONCE_BYTES)
            )
            cipher.doFinal(blob, ENVELOPE_NONCE_BYTES, blob.size - ENVELOPE_NONCE_BYTES)
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ 密钥

    /**
     * 同步账户命名空间（与 FreeNote account_namespace 一致）：
     * 配置了服务器账号（地址 + 用户名）时用服务器账号，否则用离线账户名；
     * 两端必须得到完全相同的字符串才会同组，空串表示未设置账户（退回仅按口令分组）。
     */
    fun accountNamespace(serverUrl: String, username: String, localAccount: String): String {
        val url = serverUrl.trim().trimEnd('/').lowercase()
        val user = username.trim()
        if (url.isNotEmpty() && user.isNotEmpty()) return "server:$url#$user"
        val local = localAccount.trim()
        return if (local.isNotEmpty()) "local:$local" else ""
    }

    /** 账户指纹：可对外公开的短哈希，用于提示两端账户是否一致。 */
    fun accountFingerprint(namespace: String): String =
        if (namespace.isEmpty()) {
            ""
        } else {
            sha256Hex(ACCOUNT_HASH_CONTEXT + namespace.toByteArray(Charsets.UTF_8)).take(16)
        }

    /** 组密钥：账户非空时并入 PBKDF2 salt（与 FreeNote derive_group_key 一致）。 */
    fun deriveGroupKey(passphrase: String, account: String = ""): ByteArray {
        val password = passphrase.toByteArray(Charsets.UTF_8)
        val salt = if (account.isEmpty()) {
            GROUP_SALT
        } else {
            GROUP_SALT + ACCOUNT_SALT_CONTEXT + account.toByteArray(Charsets.UTF_8)
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val block = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, block, 0, salt.size)
        block[salt.size + 3] = 1 // INT(1)，大端
        var u = mac.doFinal(block)
        val result = u.copyOf()
        for (iteration in 2..GROUP_KEY_ITERATIONS) {
            u = mac.doFinal(u)
            for (index in result.indices) {
                result[index] = (result[index].toInt() xor u[index].toInt()).toByte()
            }
        }
        return result
    }

    fun groupTag(key: ByteArray): String =
        hmacSha256(key, ANNOUNCE_CONTEXT).toHex().take(16)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    fun newNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    // ------------------------------------------------------------------ 签名

    private fun canonical(
        method: String,
        path: String,
        timestamp: Long,
        nonce: String,
        body: ByteArray
    ): ByteArray =
        "$method\n$path\n$timestamp\n$nonce\n${sha256Hex(body)}".toByteArray(Charsets.UTF_8)

    fun sign(
        key: ByteArray,
        method: String,
        path: String,
        timestamp: Long,
        nonce: String,
        body: ByteArray
    ): String = hmacSha256(key, canonical(method, path, timestamp, nonce, body)).toHex()

    fun verifySignature(
        key: ByteArray,
        method: String,
        path: String,
        timestamp: Long,
        nonce: String,
        body: ByteArray,
        signature: String
    ): Boolean {
        val expected = sign(key, method, path, timestamp, nonce, body)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            (signature.ifEmpty { "" }).toByteArray(Charsets.UTF_8)
        )
    }

    fun deviceJson(deviceId: String, deviceName: String, listenPort: Int = 0): JSONObject =
        JSONObject()
            .put("id", deviceId)
            .put("name", deviceName)
            .put("port", listenPort)

    // ------------------------------------------------------------------ LWW

    /** 清单条目（对端状态），由 [manifestFromEntries] 解析。 */
    data class ManifestEntry(
        val updatedAt: Long,
        val isDeleted: Boolean,
        val hash: String
    )

    /**
     * 内容指纹（协议 v5）：同毫秒平局的确定性仲裁依据（不含 id/updatedAt）。
     * 规范化规则与 Windows 端 content_hash_fields / 服务端一致，跨语言测试向量锁定。
     */
    fun noteContentHash(note: Note): String {
        val canonical = listOf(
            note.title,
            note.content,
            note.tags,
            note.folder,
            note.color.toString(),
            note.createdAt.toString(),
            if (note.isPinned) "1" else "0",
            if (note.isLocked) "1" else "0",
            if (note.isChecklist) "1" else "0",
            (note.reminderTime ?: -1L).toString(),
            (note.deletedAt ?: -1L).toString()
        ).joinToString("\u001F")
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8)).take(16)
    }

    /** 按 UTF-8 字节序比较（等价于 Unicode 码点序，与 Windows 端 Python 字符串排序一致）。 */
    fun compareIds(a: String, b: String): Int {
        val left = a.toByteArray(Charsets.UTF_8)
        val right = b.toByteArray(Charsets.UTF_8)
        val common = minOf(left.size, right.size)
        for (index in 0 until common) {
            val x = left[index].toInt() and 0xFF
            val y = right[index].toInt() and 0xFF
            if (x != y) return x - y
        }
        return left.size - right.size
    }

    /**
     * incoming 是否应覆盖 existing（协议 v5，与 Windows 端 incoming_wins 一致）：
     * 时间戳不同新者胜；同刻删除优先；同刻同态按内容哈希小者胜（哈希缺失保守保留本机）。
     */
    fun incomingWins(existing: ManifestEntry?, incoming: ManifestEntry): Boolean {
        if (existing == null) return true
        if (incoming.updatedAt != existing.updatedAt) return incoming.updatedAt > existing.updatedAt
        if (incoming.isDeleted != existing.isDeleted) return incoming.isDeleted && !existing.isDeleted
        val existingHash = existing.hash
        val incomingHash = incoming.hash
        return existingHash.isNotEmpty() && incomingHash.isNotEmpty() && incomingHash < existingHash
    }

    // ------------------------------------------------------------------ JSON

    fun noteToJson(note: Note): JSONObject = JSONObject()
        .put("id", note.id)
        .put("title", note.title)
        .put("content", note.content)
        .put("color", note.color)
        .put("createdAt", note.createdAt)
        .put("updatedAt", note.updatedAt)
        .put("isPinned", note.isPinned)
        .put("tags", note.tags)
        .put("folder", note.folder)
        .put("isChecklist", note.isChecklist)
        .put("isLocked", note.isLocked)
        .put("reminderTime", note.reminderTime ?: JSONObject.NULL)
        .put("isDeleted", note.isDeleted)
        .put("deletedAt", note.deletedAt ?: JSONObject.NULL)
        .put("recurrence", note.recurrence)

    fun noteFromJson(obj: JSONObject): Note {
        val created = obj.optLong("createdAt", System.currentTimeMillis())
        val updated = obj.optLong("updatedAt", created)
        val reminder = obj.optLong("reminderTime", -1L)
        val deletedAt = obj.optLong("deletedAt", -1L)
        return Note(
            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = obj.optString("title"),
            content = obj.optString("content"),
            color = obj.optLong("color", NoteColor.DEFAULT_COLORS[0]),
            createdAt = created,
            updatedAt = updated,
            isPinned = obj.optBoolean("isPinned"),
            isDeleted = obj.optBoolean("isDeleted"),
            deletedAt = deletedAt.takeIf { it > 0 },
            tags = obj.optString("tags"),
            reminderTime = reminder.takeIf { it > 0 },
            isLocked = obj.optBoolean("isLocked"),
            isChecklist = obj.optBoolean("isChecklist"),
            folder = normalizeFolder(obj.optString("folder")),
            recurrence = normalizeRecurrence(obj.optString("recurrence"))
        )
    }

    /** 硬删除墓碑：以已删除便签的形式传播。 */
    fun tombstoneNote(noteId: String, deletedAt: Long): Note = Note(
        id = noteId,
        title = "",
        content = "",
        createdAt = deletedAt,
        updatedAt = deletedAt,
        isDeleted = true,
        deletedAt = deletedAt
    )

    fun entriesPayload(entries: List<SyncEntry>): JSONArray {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("updatedAt", entry.updatedAt)
                    .put("isDeleted", entry.isDeleted)
                    .put("hash", entry.contentHash)
            )
        }
        return array
    }

    fun manifestFromEntries(raw: JSONArray?): MutableMap<String, ManifestEntry> {
        val manifest = LinkedHashMap<String, ManifestEntry>()
        if (raw == null) return manifest
        for (index in 0 until raw.length()) {
            val item = raw.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isBlank() || id == "null") continue
            manifest[id] = ManifestEntry(
                updatedAt = item.optLong("updatedAt", 0L),
                isDeleted = item.optBoolean("isDeleted"),
                hash = item.optString("hash")
            )
        }
        return manifest
    }

    // ------------------------------------------------------------------ 收发集合

    /** 按 id 收集整条便签（含尚未物化的墓碑），用于回应对端的 need 列表。 */
    suspend fun collectNotes(store: SyncStore, ids: List<String>): JSONArray {
        val tombstones = store.tombstones().associate { it.noteId to it.queuedAt }
        val array = JSONArray()
        for (id in ids) {
            if (id.isBlank()) continue
            val note = store.get(id)
            if (note != null) {
                array.put(noteToJson(note))
            } else {
                tombstones[id]?.let { array.put(noteToJson(tombstoneNote(id, it))) }
            }
        }
        return array
    }

    /** 本机有、而对方没有或应被本机版本覆盖的全部便签（含墓碑）。 */
    suspend fun notesPeerLacks(
        store: SyncStore,
        peerManifest: Map<String, ManifestEntry>
    ): JSONArray {
        val tombstones = store.tombstones().associate { it.noteId to it.queuedAt }
        val array = JSONArray()
        for (entry in store.syncEntries()) {
            val incoming = ManifestEntry(entry.updatedAt, entry.isDeleted, entry.contentHash)
            if (!incomingWins(peerManifest[entry.id], incoming)) continue
            val note = store.get(entry.id)
            if (note != null) {
                array.put(noteToJson(note))
            } else {
                tombstones[entry.id]?.let { array.put(noteToJson(tombstoneNote(entry.id, it))) }
            }
        }
        return array
    }
}

internal fun ByteArray.toHex(): String {
    val builder = StringBuilder(size * 2)
    for (byte in this) {
        builder.append("0123456789abcdef"[(byte.toInt() ushr 4) and 0x0F])
        builder.append("0123456789abcdef"[byte.toInt() and 0x0F])
    }
    return builder.toString()
}
