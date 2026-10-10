package com.zqr.freenote.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE isDeleted = 0 ORDER BY isPinned DESC, updatedAt DESC")
    fun getActiveNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getTrashedNotes(): Flow<List<Note>>

    @Query(
        """
        SELECT * FROM notes
        WHERE isDeleted = 0
          AND (title LIKE '%' || :escaped || '%' ESCAPE '\'
               OR content LIKE '%' || :escaped || '%' ESCAPE '\'
               OR tags LIKE '%' || :escaped || '%' ESCAPE '\')
        ORDER BY isPinned DESC, updatedAt DESC
        """
    )
    fun searchNotes(escaped: String): Flow<List<Note>>

    @Query(
        """
        SELECT * FROM notes
        WHERE isDeleted = 0
          AND (tags = :tag
               OR tags LIKE :tagEscaped || ',%' ESCAPE '\'
               OR tags LIKE '%,' || :tagEscaped || ',%' ESCAPE '\'
               OR tags LIKE '%,' || :tagEscaped ESCAPE '\')
        ORDER BY isPinned DESC, updatedAt DESC
        """
    )
    fun getNotesByTag(tag: String, tagEscaped: String): Flow<List<Note>>

    @Query("SELECT DISTINCT tags FROM notes WHERE isDeleted = 0 AND tags != ''")
    fun getAllTags(): Flow<List<String>>

    // ------------------------------------------------------------------ 文件夹

    @Query("SELECT DISTINCT folder FROM notes WHERE isDeleted = 0 AND folder != ''")
    fun getAllFolders(): Flow<List<String>>

    @Query(
        """
        SELECT * FROM notes
        WHERE isDeleted = 0
          AND (folder = :folder OR folder LIKE :prefix ESCAPE '\')
        ORDER BY isPinned DESC, updatedAt DESC
        """
    )
    fun getNotesInFolder(folder: String, prefix: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE isDeleted = 0 AND folder = '' ORDER BY isPinned DESC, updatedAt DESC")
    fun getNotesWithoutFolder(): Flow<List<Note>>

    /** 指定路径及其子树下的全部便签（含回收站，与 Windows rename_folder 语义一致）。 */
    @Query(
        """
        SELECT * FROM notes
        WHERE folder = :folder OR folder LIKE :prefix ESCAPE '\'
        """
    )
    suspend fun getNotesWithFolderPrefix(folder: String, prefix: String): List<Note>

    @Query("UPDATE notes SET folder = :folder, updatedAt = :time WHERE id = :id")
    suspend fun setFolder(id: String, folder: String, time: Long)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: String): Note?

    /** 按标题查找未删除便签（[[双向链接]] 跳转用；多个同名取最近修改的）。 */
    @Query("SELECT id FROM notes WHERE isDeleted = 0 AND title = :title ORDER BY updatedAt DESC, rowid DESC LIMIT 1")
    suspend fun findIdByTitle(title: String): String?

    @Query("SELECT * FROM notes WHERE isDeleted = 0")
    suspend fun getAllActiveNotes(): List<Note>

    @Query("SELECT id FROM notes")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM notes WHERE isDeleted = 0 AND reminderTime IS NOT NULL AND reminderTime > 0")
    suspend fun getNotesWithReminder(): List<Note>

    @Query("SELECT * FROM notes WHERE isLocked = 1")
    suspend fun getLockedNotes(): List<Note>

    @Query("SELECT COUNT(*) FROM notes WHERE isLocked = 1")
    suspend fun countLockedNotes(): Int

    @Query("SELECT * FROM notes WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    suspend fun getTrashedNotesNow(): List<Note>

    @Query("SELECT * FROM notes WHERE isDeleted = 1 AND deletedAt < :before")
    suspend fun getTrashExpiredBefore(before: Long): List<Note>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note)

    /** 单条 UPSERT（INSERT ... ON CONFLICT DO UPDATE），替代先查询再插入/更新的两次往返。 */
    @Upsert
    suspend fun upsert(note: Note)

    @Update
    suspend fun update(note: Note)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM notes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE notes SET isDeleted = 1, deletedAt = :time WHERE id = :id")
    suspend fun moveToTrash(id: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: String)

    @Query("DELETE FROM notes WHERE isDeleted = 1 AND deletedAt < :before")
    suspend fun purgeOldTrash(before: Long)

    @Query("UPDATE notes SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE notes SET reminderTime = :time WHERE id = :id")
    suspend fun setReminder(id: String, time: Long?)

    // ------------------------------------------------------------------ 历史版本

    @Insert
    suspend fun insertHistory(entry: NoteHistory)

    @Query("SELECT * FROM note_history WHERE noteId = :noteId ORDER BY savedAt DESC, historyId DESC")
    suspend fun historyFor(noteId: String): List<NoteHistory>

    @Query("SELECT * FROM note_history WHERE noteId = :noteId ORDER BY savedAt DESC, historyId DESC LIMIT 1")
    suspend fun latestHistory(noteId: String): NoteHistory?

    @Query("SELECT * FROM note_history WHERE historyId = :historyId")
    suspend fun historyById(historyId: Long): NoteHistory?

    @Query(
        """
        DELETE FROM note_history WHERE noteId = :noteId AND historyId NOT IN
        (SELECT historyId FROM note_history WHERE noteId = :noteId
         ORDER BY savedAt DESC, historyId DESC LIMIT :keep)
        """
    )
    suspend fun trimHistory(noteId: String, keep: Int)

    @Query("DELETE FROM note_history WHERE noteId = :noteId")
    suspend fun deleteHistoryForNote(noteId: String)

    @Query("DELETE FROM note_history WHERE noteId IN (:ids)")
    suspend fun deleteHistoryForNotes(ids: List<String>)

    // ------------------------------------------------------------------ 同步

    /** notes 全表（含已删除行）：供同步层计算条目与内容哈希。 */
    @Query("SELECT * FROM notes")
    suspend fun getAllNotesForSync(): List<Note>

    /** 已硬删除、但尚未物化为删除行的墓碑（outbox 有删除标记而 notes 无此行）。 */
    @Query(
        """
        SELECT o.note_id AS note_id, o.queued_at AS queued_at
        FROM sync_outbox o LEFT JOIN notes n ON n.id = o.note_id
        WHERE n.id IS NULL AND o.deleted = 1
        """
    )
    suspend fun tombstones(): List<TombstoneRow>

    @Query("SELECT * FROM sync_outbox ORDER BY queued_at")
    suspend fun outbox(): List<SyncOutboxEntry>

    @Query("SELECT * FROM sync_outbox WHERE note_id = :id")
    suspend fun outboxEntry(id: String): SyncOutboxEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutbox(entry: SyncOutboxEntry)

    @Query("DELETE FROM sync_outbox WHERE note_id = :noteId")
    suspend fun deleteOutbox(noteId: String)

    @Query("DELETE FROM sync_outbox WHERE note_id = :noteId AND queued_at = :queuedAt")
    suspend fun deleteOutboxExact(noteId: String, queuedAt: Long)

    @Query("SELECT value FROM sync_meta WHERE `key` = :key")
    suspend fun metaGet(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun metaSet(entry: SyncMetaEntry)
}
