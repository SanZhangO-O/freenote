package com.zqr.freenote.data.sync

import androidx.room.withTransaction
import com.zqr.freenote.data.AppDatabase
import com.zqr.freenote.data.Note
import com.zqr.freenote.data.SyncEntry
import com.zqr.freenote.data.SyncMetaEntry
import com.zqr.freenote.data.SyncOutboxEntry
import com.zqr.freenote.data.recordNoteHistory
import java.security.MessageDigest

/**
 * 同步存储门面：把 notes / sync_outbox / sync_meta 三张表组合成
 * FreeNote（Windows P2P）与 FreeNote 服务端共用的读写语义。
 */
class SyncStore(private val db: AppDatabase) {
    private val dao = db.noteDao()

    /**
     * 附件清单提供者（协议 v6）：由 SyncManager 注入，
     * 返回 (引用集合, 引用且本机已有的集合)；未注入时摘要不含附件段。
     */
    @Volatile
    var attachmentInventoryProvider: (suspend () -> Pair<Set<String>, Set<String>>)? = null

    suspend fun get(id: String): Note? = dao.getNoteById(id)

    /** 同步写入：保留远端 updatedAt，不产生待传播变更（覆盖前把旧版本存入历史）。 */
    suspend fun applyRemote(note: Note) {
        dao.getNoteById(note.id)?.let { recordNoteHistory(dao, it) }
        dao.upsert(note)
    }

    /**
     * LWW 应用远端便签；返回是否被采纳（协议 v5，与 incoming_wins 一致）：
     * 新者胜 → 同刻删除优先 → 同刻同态按内容哈希小者胜。
     */
    suspend fun applyRemoteLww(note: Note): Boolean = db.withTransaction {
        val existing = dao.getNoteById(note.id)
        val wins = when {
            existing == null -> true
            note.updatedAt != existing.updatedAt -> note.updatedAt > existing.updatedAt
            note.isDeleted != existing.isDeleted -> note.isDeleted
            else -> {
                val incomingHash = SyncProtocol.noteContentHash(note)
                val existingHash = SyncProtocol.noteContentHash(existing)
                incomingHash.isNotEmpty() && existingHash.isNotEmpty() && incomingHash < existingHash
            }
        }
        if (wins) {
            // 覆盖前把被替换的本地版本存入历史（本地历史，不跨设备同步）
            existing?.let { recordNoteHistory(dao, it) }
            dao.upsert(note)
        }
        wins
    }

    /** 本地写入后入队；删除标记只增不减（与 FreeNote 的 outbox 语义一致）。 */
    suspend fun enqueue(noteId: String, deleted: Boolean = false, at: Long = System.currentTimeMillis()) {
        if (noteId.isBlank()) return
        db.withTransaction {
            val existing = dao.outboxEntry(noteId)
            val merged = SyncOutboxEntry(
                noteId = noteId,
                deleted = deleted || existing?.deleted == true,
                queuedAt = at
            )
            dao.upsertOutbox(merged)
        }
    }

    /** 供仓库层把「写库 + 入队」放进同一数据库事务（Room 支持嵌套事务）。 */
    suspend fun <R> withTransaction(block: suspend () -> R): R = db.withTransaction { block() }

    suspend fun outbox(): List<SyncOutboxEntry> = dao.outbox()

    /** 按 note_id + queued_at 精确清除已传播的队列项（期间的新修改会保留）。 */
    suspend fun outboxClear(entries: List<SyncOutboxEntry>) {
        if (entries.isEmpty()) return
        db.withTransaction {
            entries.forEach { dao.deleteOutboxExact(it.noteId, it.queuedAt) }
        }
    }

    suspend fun tombstones() = dao.tombstones()

    /** 把墓碑写成正式的删除行（保留 updatedAt = deletedAt），并清除队列项。 */
    suspend fun materializeTombstone(noteId: String, deletedAt: Long) {
        db.withTransaction {
            if (dao.getNoteById(noteId) == null) {
                dao.upsert(
                    Note(
                        id = noteId,
                        title = "",
                        content = "",
                        createdAt = deletedAt,
                        updatedAt = deletedAt,
                        isDeleted = true,
                        deletedAt = deletedAt
                    )
                )
            }
            // 仅当队列项仍是这条墓碑（queued_at 相等）才清除；
            // 若已被更新的 enqueue 覆盖则保留，避免服务器同步漏推
            dao.deleteOutboxExact(noteId, deletedAt)
        }
    }

    suspend fun materializeAllTombstones() {
        tombstones().forEach { materializeTombstone(it.noteId, it.queuedAt) }
    }

    /**
     * 全部同步条目（id / updatedAt / isDeleted / 内容哈希），
     * 按 UTF-8 字节序（Unicode 码点序，与 Windows 端一致）排序；
     * 含尚未物化的墓碑。
     */
    suspend fun syncEntries(): List<SyncEntry> {
        val entries = dao.getAllNotesForSync().map { note ->
            SyncEntry(
                id = note.id,
                updatedAt = note.updatedAt,
                isDeleted = note.isDeleted,
                contentHash = SyncProtocol.noteContentHash(note)
            )
        }
        val known = entries.mapTo(HashSet()) { it.id }
        val withTombstones = entries.toMutableList()
        tombstones().forEach { tombstone ->
            if (tombstone.noteId !in known) {
                val tombstoneNote = SyncProtocol.tombstoneNote(tombstone.noteId, tombstone.queuedAt)
                withTombstones.add(
                    SyncEntry(
                        id = tombstone.noteId,
                        updatedAt = tombstone.queuedAt,
                        isDeleted = true,
                        contentHash = SyncProtocol.noteContentHash(tombstoneNote)
                    )
                )
            }
        }
        withTombstones.sortWith { a, b -> SyncProtocol.compareIds(a.id, b.id) }
        return withTombstones
    }

    /**
     * 同步状态摘要；两端完全一致时摘要相同（内容哈希参与，协议 v5）。
     * 协议 v6：附件引用状态（引用名 → 本地是否存在）同样参与摘要，
     * 「便签一致但图片缺失」的设备也会被发现并触发附件补传（与 Windows 端一致）。
     */
    suspend fun stateDigest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (entry in syncEntries()) {
            val flag = if (entry.isDeleted) 1 else 0
            digest.update("${entry.id}:${entry.updatedAt}:$flag:${entry.contentHash}\n".toByteArray(Charsets.UTF_8))
        }
        digest.update(0x1e.toByte())
        attachmentInventoryProvider?.invoke()?.let { (referenced, present) ->
            for (name in referenced.sorted()) {
                digest.update(
                    "$name:${if (name in present) 1 else 0}\n".toByteArray(Charsets.UTF_8)
                )
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun metaGet(key: String, default: String? = null): String? =
        dao.metaGet(key) ?: default

    suspend fun metaSet(key: String, value: String) {
        dao.metaSet(SyncMetaEntry(key, value))
    }
}
