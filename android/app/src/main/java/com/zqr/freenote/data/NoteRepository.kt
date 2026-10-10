package com.zqr.freenote.data

import com.zqr.freenote.data.sync.SyncStore
import com.zqr.freenote.util.SqlLike
import kotlinx.coroutines.flow.Flow

/**
 * 便签仓库：所有本地写入都会同时记入同步待发队列（sync_outbox），
 * 供 P2P / 服务器同步把增量传播出去。
 */
class NoteRepository(
    private val noteDao: NoteDao,
    private val syncStore: SyncStore? = null
) {
    val activeNotes: Flow<List<Note>> = noteDao.getActiveNotes()
    val trashedNotes: Flow<List<Note>> = noteDao.getTrashedNotes()
    val allTags: Flow<List<String>> = noteDao.getAllTags()
    val allFolders: Flow<List<String>> = noteDao.getAllFolders()

    fun searchNotes(escapedQuery: String): Flow<List<Note>> = noteDao.searchNotes(escapedQuery)
    fun notesByTag(tag: String): Flow<List<Note>> =
        noteDao.getNotesByTag(tag, SqlLike.escape(tag))

    /** folder 为空串时取未分类；否则取该文件夹及其全部子文件夹下的便签。 */
    fun notesInFolder(folder: String): Flow<List<Note>> =
        if (folder.isEmpty()) noteDao.getNotesWithoutFolder()
        else noteDao.getNotesInFolder(folder, SqlLike.escape(folder) + "/%")

    suspend fun getNoteById(id: String): Note? = noteDao.getNoteById(id)
    suspend fun getAllActiveNotes(): List<Note> = noteDao.getAllActiveNotes()
    suspend fun getNotesWithReminder(): List<Note> = noteDao.getNotesWithReminder()
    suspend fun getLockedNotes(): List<Note> = noteDao.getLockedNotes()
    suspend fun countLockedNotes(): Int = noteDao.countLockedNotes()
    suspend fun getTrashedNotesNow(): List<Note> = noteDao.getTrashedNotesNow()

    suspend fun upsert(note: Note) {
        val store = syncStore
        if (store == null) {
            noteDao.getNoteById(note.id)?.let { recordNoteHistory(noteDao, it) }
            noteDao.upsert(note)
        } else {
            // 写库与入队同事务，避免同步层读到中间状态
            store.withTransaction {
                noteDao.getNoteById(note.id)?.let { recordNoteHistory(noteDao, it) }
                noteDao.upsert(note)
                store.enqueue(note.id)
            }
        }
    }

    suspend fun update(note: Note) {
        val store = syncStore
        if (store == null) {
            noteDao.getNoteById(note.id)?.let { recordNoteHistory(noteDao, it) }
            noteDao.update(note)
        } else {
            store.withTransaction {
                noteDao.getNoteById(note.id)?.let { recordNoteHistory(noteDao, it) }
                noteDao.update(note)
                store.enqueue(note.id)
            }
        }
    }

    suspend fun deleteById(id: String) {
        val store = syncStore
        if (store == null) {
            noteDao.deleteById(id)
            noteDao.deleteHistoryForNote(id)
        } else {
            store.withTransaction {
                noteDao.deleteById(id)
                noteDao.deleteHistoryForNote(id)
                store.enqueue(id, deleted = true)
            }
        }
    }

    suspend fun deleteByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        val store = syncStore
        if (store == null) {
            noteDao.deleteByIds(ids)
            noteDao.deleteHistoryForNotes(ids)
        } else {
            store.withTransaction {
                noteDao.deleteByIds(ids)
                noteDao.deleteHistoryForNotes(ids)
                ids.forEach { store.enqueue(it, deleted = true) }
            }
        }
    }

    suspend fun moveToTrash(id: String) {
        val store = syncStore
        if (store == null) {
            noteDao.moveToTrash(id)
        } else {
            store.withTransaction {
                noteDao.moveToTrash(id)
                store.enqueue(id)
            }
        }
    }

    suspend fun restoreFromTrash(id: String) {
        val store = syncStore
        if (store == null) {
            noteDao.restoreFromTrash(id)
        } else {
            store.withTransaction {
                noteDao.restoreFromTrash(id)
                store.enqueue(id)
            }
        }
    }

    /** 删除过期回收站记录并返回被删记录，供调用方清理关联的图片文件。 */
    suspend fun purgeOldTrash(before: Long): List<Note> {
        val store = syncStore
        if (store == null) {
            val expired = noteDao.getTrashExpiredBefore(before)
            if (expired.isNotEmpty()) noteDao.purgeOldTrash(before)
            return expired
        }
        // 查询 + 删除 + 入队整体放同一事务，保证一致
        return store.withTransaction {
            val expired = noteDao.getTrashExpiredBefore(before)
            if (expired.isNotEmpty()) {
                noteDao.purgeOldTrash(before)
                // 墓碑沿用原删除时间，保证两端 LWW 与摘要稳定
                expired.forEach {
                    store.enqueue(it.id, deleted = true, at = it.deletedAt ?: System.currentTimeMillis())
                }
            }
            expired
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        val store = syncStore
        if (store == null) {
            noteDao.setPinned(id, pinned)
        } else {
            store.withTransaction {
                noteDao.setPinned(id, pinned)
                store.enqueue(id)
            }
        }
    }

    suspend fun setReminder(id: String, time: Long?) {
        val store = syncStore
        if (store == null) {
            noteDao.setReminder(id, time)
        } else {
            store.withTransaction {
                noteDao.setReminder(id, time)
                store.enqueue(id)
            }
        }
    }

    // ------------------------------------------------------------------ 历史版本

    suspend fun historyFor(noteId: String): List<NoteHistory> = noteDao.historyFor(noteId)

    /** 按标题查找未删除便签 id（[[双向链接]] 跳转）。 */
    suspend fun findIdByTitle(title: String): String? {
        val clean = title.trim()
        return if (clean.isEmpty()) null else noteDao.findIdByTitle(clean)
    }

    /** 恢复历史版本；返回恢复后的便签（失败返回 null）。恢复前的当前状态也会存入历史。 */
    suspend fun restoreHistory(noteId: String, historyId: Long): Note? {
        val entry = noteDao.historyById(historyId) ?: return null
        if (entry.noteId != noteId) return null
        val current = noteDao.getNoteById(noteId) ?: return null
        recordNoteHistory(noteDao, current, force = true)
        val restored = current.copy(
            title = entry.title,
            content = entry.content,
            color = entry.color,
            tags = entry.tags,
            folder = entry.folder,
            isPinned = entry.isPinned,
            isLocked = entry.isLocked,
            isChecklist = entry.isChecklist,
            reminderTime = entry.reminderTime,
            recurrence = entry.recurrence,
            updatedAt = System.currentTimeMillis()
        )
        upsert(restored)
        return restored
    }

    // ------------------------------------------------------------------ 文件夹

    /** 把旧路径及其子树的 folder 前缀改写为新路径；返回受影响的便签数。 */
    suspend fun renameFolder(old: String, new: String): Int {
        val from = normalizeFolder(old)
        val to = normalizeFolder(new)
        if (from.isEmpty() || to.isEmpty() || from == to) return 0
        return rewriteFolderPrefix(from) { current -> to + current.substring(from.length) }
    }

    /** 删除文件夹：其下（含子文件夹）便签上移到父级，绝不删除便签。 */
    suspend fun deleteFolder(folder: String): Int {
        val from = normalizeFolder(folder)
        if (from.isEmpty()) return 0
        val parent = folderParent(from)
        return rewriteFolderPrefix(from) { current ->
            val suffix = current.substring(from.length)
            if (parent.isEmpty()) suffix.trimStart('/') else parent + suffix
        }
    }

    private suspend fun rewriteFolderPrefix(from: String, rewrite: (String) -> String): Int {
        val prefix = SqlLike.escape(from) + "/%"
        val store = syncStore
        if (store == null) {
            val rows = noteDao.getNotesWithFolderPrefix(from, prefix)
            val now = System.currentTimeMillis()
            rows.forEach { noteDao.setFolder(it.id, rewrite(it.folder), now) }
            return rows.size
        }
        // 查询、取时间戳与更新放同一事务：快速连续的重命名/删除不会读到未提交的中间状态，
        // 也不会把命中行的 updatedAt 回写成早于并发编辑的旧时间戳
        return store.withTransaction {
            val rows = noteDao.getNotesWithFolderPrefix(from, prefix)
            val now = System.currentTimeMillis()
            rows.forEach {
                noteDao.setFolder(it.id, rewrite(it.folder), now)
                store.enqueue(it.id)
            }
            rows.size
        }
    }
}
