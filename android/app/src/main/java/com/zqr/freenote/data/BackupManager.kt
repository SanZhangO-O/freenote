package com.zqr.freenote.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.zqr.freenote.data.sync.SyncProtocol
import com.zqr.freenote.data.sync.SyncStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object BackupManager {

    data class BackupResult(val success: Boolean, val message: String)

    /** 备份文件格式版本：5 起为 legacy，6 起附带内容寻址图片附件（attachments 数组）。 */
    private const val BACKUP_VERSION = 6
    private const val MAX_EXPORT_IMAGE_BYTES = 64 * 1024 * 1024
    /** 单次导出图片总量上限（base64 后更大，需控制内存占用）。 */
    private const val MAX_EXPORT_TOTAL_IMAGE_BYTES = 64 * 1024 * 1024

    private data class ExportPayload(
        val root: JSONObject,
        val attachmentCount: Int,
        val skippedCount: Int,
        val truncated: Boolean = false
    )

    /** 构建备份 JSON 根对象（文件导出与自动备份共用）。 */
    private fun buildExportPayload(context: Context, notes: List<Note>): ExportPayload {
        val array = JSONArray()
        notes.forEach { note ->
            val obj = JSONObject()
            obj.put("id", note.id)
            obj.put("title", note.title)
            obj.put("content", note.content)
            obj.put("color", note.color)
            obj.put("createdAt", note.createdAt)
            obj.put("updatedAt", note.updatedAt)
            obj.put("isPinned", note.isPinned)
            obj.put("tags", note.tags)
            obj.put("folder", note.folder)
            obj.put("isChecklist", note.isChecklist)
            obj.put("isLocked", note.isLocked)
            obj.put("reminderTime", note.reminderTime ?: JSONObject.NULL)
            obj.put("isDeleted", note.isDeleted)
            obj.put("deletedAt", note.deletedAt ?: JSONObject.NULL)
            obj.put("recurrence", note.recurrence)
            array.put(obj)
        }

        // 图片附件（协议 v6）：导出被便签引用的本地图片（内容寻址，含 base64 字节）
        val imageNames = HashSet<String>()
        notes.forEach { note ->
            if (!note.isLocked) imageNames.addAll(contentAttachmentNames(note.content))
        }
        val attachmentsStore = AttachmentStore(context, AppDatabase.getDatabase(context))
        val blobs = JSONArray()
        var skipped = 0
        var truncated = false
        var totalBytes = 0L
        for (name in imageNames.sorted()) {
            val data = attachmentsStore.blobData(name) ?: continue
            if (data.size > MAX_EXPORT_IMAGE_BYTES) {
                skipped++
                continue
            }
            if (totalBytes + data.size > MAX_EXPORT_TOTAL_IMAGE_BYTES) {
                truncated = true
                break
            }
            blobs.put(
                JSONObject().put("name", name).put("data", attachmentsStore.encodeWire(data))
            )
            totalBytes += data.size
        }

        val root = JSONObject()
        root.put("app", "FreeNote")
        root.put("version", BACKUP_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("notes", array)
        root.put("attachments", blobs)
        return ExportPayload(root, blobs.length(), skipped, truncated)
    }

    fun export(context: Context, notes: List<Note>, uri: Uri): BackupResult {
        return try {
            val payload = buildExportPayload(context, notes)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(payload.root.toString(2).toByteArray())
            } ?: return BackupResult(false, "无法写入文件")

            var message = "导出成功：${notes.size} 条，含图片 ${payload.attachmentCount} 张"
            if (payload.skippedCount > 0) message += "（跳过 ${payload.skippedCount} 张超大图片）"
            if (payload.truncated) message += "（图片总量超出单次导出上限，其余图片未包含）"
            BackupResult(true, message)
        } catch (e: Exception) {
            BackupResult(false, "导出失败：${e.message}")
        }
    }

    /** 自动备份到 SAF 目录（Tree URI）：写入 JSON 备份 + 可选 WebDAV 上传。 */
    suspend fun autoBackup(context: Context, treeUri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            val database = AppDatabase.getDatabase(context)
            val notes = database.noteDao().getAllActiveNotes() + database.noteDao().getTrashedNotesNow()
            val payload = buildExportPayload(context, notes)
            val name = "FreeNote-" +
                android.text.format.DateFormat.format("yyyyMMdd-HHmmss", System.currentTimeMillis()) +
                ".json"
            val docUri = createDocument(context, treeUri, name)
                ?: return@withContext BackupResult(
                    false, "无法在备份目录创建文件（权限可能已失效，请重新选择文件夹）"
                )
            val bytes = payload.root.toString(2).toByteArray()
            context.contentResolver.openOutputStream(docUri)?.use { it.write(bytes) }
                ?: return@withContext BackupResult(false, "无法写入备份文件")
            SettingsManager.setLastAutoBackupAt(System.currentTimeMillis())
            var message = "自动备份完成：${notes.size} 条 → $name"
            val webdavError = uploadWebdav(name, bytes)
            if (webdavError != null) {
                return@withContext BackupResult(false, "$message；WebDAV 上传失败：$webdavError")
            }
            if (SettingsManager.webdavUrl().isNotBlank()) message += "；已上传 WebDAV"
            BackupResult(true, message)
        } catch (e: Exception) {
            BackupResult(false, "自动备份失败：${e.message}")
        }
    }

    /** 在 SAF Tree URI 下创建文档（直接使用 DocumentsContract，不引入额外依赖）。 */
    private fun createDocument(context: Context, treeUri: Uri, name: String): Uri? = runCatching {
        val documentId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        android.provider.DocumentsContract.createDocument(
            context.contentResolver, parent, "application/json", name
        )
    }.getOrNull()

    /** 可选 WebDAV 上传（HTTP 基本认证）；未配置返回 null。 */
    private fun uploadWebdav(name: String, data: ByteArray): String? {
        val base = SettingsManager.webdavUrl()
        if (base.isBlank()) return null
        val target = base.trimEnd('/') + "/" +
            java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return try {
            val connection = (java.net.URL(target).openConnection() as java.net.HttpURLConnection)
                .apply {
                    requestMethod = "PUT"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("Content-Type", "application/json")
                    val user = SettingsManager.webdavUser()
                    if (user.isNotBlank()) {
                        val token = android.util.Base64.encodeToString(
                            "$user:${SettingsManager.webdavPassword()}".toByteArray(),
                            android.util.Base64.NO_WRAP
                        )
                        setRequestProperty("Authorization", "Basic $token")
                    }
                }
            connection.outputStream.use { it.write(data) }
            val code = connection.responseCode
            connection.disconnect()
            if (code >= 300) "HTTP $code" else null
        } catch (e: Exception) {
            e.message ?: "网络错误"
        }
    }

    /** 从文件读取 .md/.txt 并导入为新便签（每个文件一条）。 */
    suspend fun importTextFiles(context: Context, uris: List<Uri>): BackupResult =
        withContext(Dispatchers.IO) {
            val database = AppDatabase.getDatabase(context)
            val dao = database.noteDao()
            val syncStore = SyncStore(database)
            var imported = 0
            var failed = 0
            for (uri in uris) {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
                if (text.isNullOrBlank()) {
                    // 空文件也允许导入为一条空便签，但读取失败要计入跳过
                    if (text == null) {
                        failed++
                        continue
                    }
                }
                val displayName = queryDisplayName(context, uri) ?: "导入便签.md"
                if (displayName.endsWith(".enex", ignoreCase = true)) {
                    val count = importEnex(text, database, syncStore)
                    if (count >= 0) imported += count else failed++
                    continue
                }
                val (title, content) = parseTextNote(displayName, text)
                val stamp = System.currentTimeMillis()
                val note = Note(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    content = content,
                    createdAt = stamp,
                    updatedAt = stamp
                )
                database.withTransaction {
                    dao.upsert(note)
                    syncStore.enqueue(note.id)
                }
                imported++
            }
            if (imported == 0) {
                BackupResult(false, "导入失败：文件无法读取")
            } else {
                var message = "导入完成：新增 $imported 条文本便签"
                if (failed > 0) message += "，$failed 个文件跳过"
                BackupResult(true, message)
            }
        }

    /** ENEX（Evernote 导出）解析：每个 <note> 展开为一条便签（-1 表示解析失败）。 */
    private suspend fun importEnex(text: String?, database: AppDatabase, syncStore: SyncStore): Int {
        if (text.isNullOrBlank()) return -1
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val document = try {
            factory.newDocumentBuilder()
                .parse(org.xml.sax.InputSource(java.io.StringReader(text)))
        } catch (e: Exception) {
            return -1
        }
        val nodes = document.getElementsByTagName("note")
        var imported = 0
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? org.w3c.dom.Element ?: continue
            val title = element.getElementsByTagName("title").item(0)?.textContent?.trim().orEmpty()
            val rawContent = element.getElementsByTagName("content").item(0)?.textContent.orEmpty()
            val content = enmlToText(rawContent)
            if (title.isEmpty() && content.isEmpty()) continue
            val created = element.getElementsByTagName("created").item(0)?.textContent?.trim().orEmpty()
            val stamp = parseEnexTime(created)
            val note = Note(
                id = UUID.randomUUID().toString(),
                title = title.ifBlank { "Evernote 便签" }.take(200),
                content = content,
                createdAt = stamp,
                updatedAt = stamp
            )
            database.withTransaction {
                database.noteDao().upsert(note)
                syncStore.enqueue(note.id)
            }
            imported++
        }
        return imported
    }

    /** ENML（HTML 子集）→ 纯文本/Markdown：保留标题与列表结构，附件以占位文字标记。 */
    private fun enmlToText(raw: String): String {
        var text = raw
        text = Regex("(?is)<en-media[^>]*>").replace(text, "[Evernote 附件未导入]")
        text = Regex("(?is)<br\\s*/?>").replace(text, "\n")
        text = Regex("(?is)<h1[^>]*>").replace(text, "\n# ")
        text = Regex("(?is)<h2[^>]*>").replace(text, "\n## ")
        text = Regex("(?is)<h3[^>]*>").replace(text, "\n### ")
        text = Regex("(?is)<li[^>]*>").replace(text, "\n- ")
        text = Regex("(?is)</(div|p|h1|h2|h3|li|ul|ol|en-note)>").replace(text, "\n")
        text = Regex("(?is)<[^>]+>").replace(text, "")
        return text.lines()
            .map { it.trimEnd() }
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
    }

    /** ENEX created 字段（UTC）→ 毫秒时间戳；解析失败用当前时间。 */
    private fun parseEnexTime(value: String): Long {
        if (value.isBlank()) return System.currentTimeMillis()
        for (pattern in listOf("yyyyMMdd'T'HHmmss'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try {
                val format = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val parsed = format.parse(value) ?: continue
                return parsed.time
            } catch (e: Exception) {
                continue
            }
        }
        return System.currentTimeMillis()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    /** 解析文本：首个 `# ` 一级标题作为标题（并从正文移除），否则用文件名。 */
    private fun parseTextNote(displayName: String, text: String): Pair<String, String> {
        val fallbackTitle = displayName.substringBeforeLast('.').trim().ifBlank { "导入便签" }
        val lines = text.lines()
        for (index in 0 until minOf(lines.size, 10)) {
            val line = lines[index]
            if (line.startsWith("# ")) {
                val title = line.removePrefix("# ").trim().ifBlank { fallbackTitle }
                val content = (lines.take(index) + lines.drop(index + 1)).joinToString("\n").trim()
                return title.take(200) to content
            }
        }
        return fallbackTitle.take(200) to text.trim()
    }

    suspend fun import(context: Context, uri: Uri): BackupResult {
        return try {
            val json = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: return BackupResult(false, "无法读取文件")

            val root = JSONObject(json)
            val array = root.optJSONArray("notes") ?: return BackupResult(false, "文件格式无效")
            val database = AppDatabase.getDatabase(context)
            val dao = database.noteDao()
            val syncStore = SyncStore(database)

            // 先落盘图片附件（协议 v6；缺失该字段的旧备份不受影响）
            var importedImages = 0
            val attachmentsStore = AttachmentStore(context, database)
            root.optJSONArray("attachments")?.let { blobs ->
                for (index in 0 until blobs.length()) {
                    val item = blobs.optJSONObject(index) ?: continue
                    val name = item.optString("name")
                    val data = attachmentsStore.decodeWire(item.optString("data"))
                    if (data != null && attachmentsStore.storeBlob(name, data)) importedImages++
                }
            }

            var count = 0
            var updated = 0
            var skipped = 0
            var locked = 0
            var trashed = 0
            // 单一事务：既保证每条「落库 + 入队」原子（不同步层读到中间状态），
            // 也保证导入整体原子（中途失败全部回滚，不会留下半批已导入数据）
            database.withTransaction {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
                    val isLocked = obj.optBoolean("isLocked")
                    val note = Note(
                        id = id,
                        title = obj.optString("title"),
                        content = obj.optString("content"),
                        color = obj.optLong("color", NoteColor.DEFAULT_COLORS[0]),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                        isPinned = obj.optBoolean("isPinned"),
                        isDeleted = obj.optBoolean("isDeleted"),
                        deletedAt = obj.optLong("deletedAt", -1).takeIf { it > 0 },
                        tags = obj.optString("tags"),
                        reminderTime = obj.optLong("reminderTime", -1).takeIf { it > 0 },
                        isLocked = isLocked,
                        isChecklist = obj.optBoolean("isChecklist"),
                        folder = normalizeFolder(obj.optString("folder"))
                    )
                    val local = dao.getNoteById(id)
                    if (local == null) {
                        dao.insert(note)
                        syncStore.enqueue(note.id)
                        count++
                    } else if (
                        SyncProtocol.incomingWins(
                            SyncProtocol.ManifestEntry(
                                local.updatedAt, local.isDeleted, SyncProtocol.noteContentHash(local)
                            ),
                            SyncProtocol.ManifestEntry(
                                note.updatedAt, note.isDeleted, SyncProtocol.noteContentHash(note)
                            )
                        )
                    ) {
                        // 备份里的版本较新：覆盖本机旧副本，并纳入同步队列
                        dao.update(note)
                        syncStore.enqueue(note.id)
                        updated++
                    } else {
                        // 本机版本较新（或内容相同）：保留本机数据
                        skipped++
                        continue
                    }
                    if (isLocked) locked++
                    if (note.isDeleted) trashed++
                }
            }
            val parts = mutableListOf("新增 $count 条")
            if (updated > 0) parts.add("更新 $updated 条")
            if (skipped > 0) parts.add("跳过 $skipped 条（本机版本较新）")
            var message = "导入完成：" + parts.joinToString("，")
            if (importedImages > 0) message += "；含图片 $importedImages 张"
            if (trashed > 0) message += "；其中 $trashed 条为回收站便签"
            if (locked > 0) message += "；含 $locked 条加密便签，需使用原 PIN 解锁"
            BackupResult(true, message)
        } catch (e: org.json.JSONException) {
            BackupResult(false, "文件不是有效的 FreeNote 备份（JSON 解析失败）")
        } catch (e: Exception) {
            BackupResult(false, "导入失败，请确认文件完整且在导出该备份的设备上重试")
        }
    }
}
