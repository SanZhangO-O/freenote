package com.zqr.freenote.data

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import com.zqr.freenote.data.sync.SyncStore
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray

/**
 * 附件（图片）存储（协议 v6）：
 * - 内容寻址命名：sha256(字节) 前 16 位 + 小写扩展名；
 * - 正文以 `![](freenote-img:<name>)` 引用；
 * - legacy 的 `file://` 图片在启动时迁移为便携标记（复制进图片目录）；
 * - P2P / 服务器同步按 name 传输字节，收到后用 name 里的哈希前缀校验完整性。
 */
class AttachmentStore(private val context: Context, private val db: AppDatabase) {
    private val dao = db.noteDao()

    companion object {
        const val MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024
        const val ATTACHMENTS_PER_MESSAGE = 8
        const val ATTACHMENT_BYTES_PER_MESSAGE = 8 * 1024 * 1024
        const val INVENTORY_LIMIT = 5000
        private const val HASH_CHUNK = 64 * 1024

        fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        private fun safeExt(suffix: String?): String {
            val cleaned = (suffix ?: "").lowercase().replace(Regex("[^a-z0-9]"), "").take(8)
            return cleaned.ifEmpty { "img" }
        }

        fun nameForBytes(data: ByteArray, suffix: String): String =
            sha256Hex(data).take(16) + "." + safeExt(suffix)

        /** 按文件内容计算附件名；ext 为空时使用文件扩展名（流式，避免大图全量载入）。 */
        fun nameForFile(file: File, ext: String? = null): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(HASH_CHUNK)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            return hex.take(16) + "." + safeExt(ext ?: file.extension)
        }
    }

    val dir: File get() = File(context.filesDir, "note_images").apply { mkdirs() }

    fun localPath(name: String): File = File(dir, name)

    fun localNames(): Set<String> =
        dir.listFiles()
            ?.filter { it.isFile && validAttachmentName(it.name) }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

    /** 全部便签（含回收站）引用的便携附件名（锁定便签正文为密文，跳过）。 */
    suspend fun referencedNames(): Set<String> {
        val names = HashSet<String>()
        dao.getAllNotesForSync().forEach { note ->
            if (!note.isLocked) names.addAll(contentAttachmentNames(note.content))
        }
        return names
    }

    /** 返回 (引用集合, 其中本机已有的集合)。 */
    suspend fun inventory(): Pair<Set<String>, Set<String>> {
        val referenced = referencedNames()
        return referenced to (referenced intersect localNames())
    }

    suspend fun missingNames(): List<String> {
        val (referenced, present) = inventory()
        return (referenced - present).sorted()
    }

    /** 写入收到的附件；名称与内容哈希不符、超限或写盘失败返回 false。 */
    fun storeBlob(name: String, data: ByteArray): Boolean {
        if (!validAttachmentName(name) || data.isEmpty() || data.size > MAX_ATTACHMENT_BYTES) {
            return false
        }
        if (!name.startsWith(sha256Hex(data).take(16))) return false
        val target = localPath(name)
        if (target.isFile) return true
        return try {
            val temp = File(dir, name + ".part")
            temp.writeBytes(data)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun blobData(name: String): ByteArray? {
        if (!validAttachmentName(name)) return null
        val file = localPath(name)
        return try {
            if (!file.isFile || file.length() > MAX_ATTACHMENT_BYTES) null else file.readBytes()
        } catch (e: Exception) {
            null
        }
    }

    fun encodeWire(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    fun decodeWire(text: String?): ByteArray? {
        if (text.isNullOrEmpty()) return null
        if (text.length > (MAX_ATTACHMENT_BYTES * 4 / 3) + 64) return null
        return try {
            val data = Base64.decode(text, Base64.DEFAULT)
            if (data.isEmpty() || data.size > MAX_ATTACHMENT_BYTES) null else data
        } catch (e: Exception) {
            null
        }
    }

    /** 解析对端传来的附件名列表：去重、校验命名、截断上限。 */
    fun validNameList(raw: JSONArray?): List<String> {
        if (raw == null) return emptyList()
        val seen = HashSet<String>()
        val names = ArrayList<String>()
        for (index in 0 until raw.length()) {
            val name = raw.optString(index)
            if (name.isBlank() || !validAttachmentName(name) || !seen.add(name)) continue
            names.add(name)
            if (names.size >= INVENTORY_LIMIT) break
        }
        return names
    }

    /** 按数量与字节上限从候选名中取出一批本地可读的附件名。 */
    fun takeBatch(names: List<String>): List<String> {
        val batch = ArrayList<String>()
        var total = 0L
        for (name in names) {
            if (batch.size >= ATTACHMENTS_PER_MESSAGE) break
            val size = try {
                localPath(name).length()
            } catch (e: Exception) {
                continue
            }
            if (size <= 0 || size > MAX_ATTACHMENT_BYTES) continue
            if (total + size > ATTACHMENT_BYTES_PER_MESSAGE) break
            batch.add(name)
            total += size
        }
        return batch
    }

    // ------------------------------------------------------------------ 迁移

    /** 把一段正文里可解析的 legacy `file://` 图片行改写为便携标记。 */
    fun migrateContent(content: String): Pair<String, Int> {
        var changed = 0
        val lines = content.split("\n").map { line ->
            val path = imageTokenPath(line)
            if (path == null || attachmentNameFromPath(path) != null || !path.startsWith("file://")) {
                line
            } else {
                val source = File(localImagePath(path))
                try {
                    if (!source.isFile) {
                        line
                    } else {
                        val name = nameForFile(source)
                        val target = localPath(name)
                        if (!target.isFile) source.copyTo(target, overwrite = false)
                        changed += 1
                        attachmentToken(name)
                    }
                } catch (e: Exception) {
                    line
                }
            }
        }
        return if (changed == 0) content to 0 else lines.joinToString("\n") to changed
    }

    /** 扫描并迁移未锁定便签的 legacy 图片引用；返回迁移的图片张数。 */
    suspend fun migrateStore(): Int {
        var images = 0
        val notes = dao.getAllNotesForSync().filter {
            !it.isLocked && it.content.contains("](file://")
        }
        for (note in notes) {
            val (newContent, changed) = migrateContent(note.content)
            if (changed == 0) continue
            images += changed
            db.withTransaction {
                dao.update(note.copy(content = newContent, updatedAt = System.currentTimeMillis()))
                SyncStore(db).enqueue(note.id)
            }
        }
        return images
    }

    /** 删除不再被任何便签引用的本地附件。 */
    suspend fun deleteUnused(names: Set<String>) {
        if (names.isEmpty()) return
        val referenced = referencedNames()
        names.forEach { name ->
            if (name !in referenced) runCatching { localPath(name).delete() }
        }
    }
}
