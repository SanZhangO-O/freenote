package com.zqr.freenote.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zqr.freenote.data.sync.SyncProtocol

/** 历史版本：每条便签最多保留 30 个版本；距上次记录不足 5 分钟的连续保存合并跳过。 */
const val HISTORY_LIMIT = 30
const val HISTORY_MIN_INTERVAL_MS = 5 * 60 * 1000L

// indices 必须与 MIGRATION_3_4 手写创建的索引一致，否则 Room 迁移校验会失败
@Entity(tableName = "note_history", indices = [Index(value = ["noteId", "savedAt"])])
data class NoteHistory(
    @PrimaryKey(autoGenerate = true) val historyId: Long = 0,
    val noteId: String,
    val savedAt: Long,
    val title: String,
    val content: String,
    val color: Long,
    val createdAt: Long,
    val tags: String,
    val folder: String,
    val isPinned: Boolean,
    val isLocked: Boolean,
    val isChecklist: Boolean,
    val reminderTime: Long?,
    val recurrence: String,
    val contentHash: String
)

/**
 * 把 [before]（即将被覆盖的当前状态）写入历史。
 * 同内容跳过；5 分钟内的连续保存合并跳过（[force] 用于「恢复前记录当前状态」）。
 * 所有调用都必须处在事务/IO 上下文中。
 */
suspend fun recordNoteHistory(dao: NoteDao, before: Note, force: Boolean = false) {
    val hash = SyncProtocol.noteContentHash(before)
    val last = dao.latestHistory(before.id)
    val now = System.currentTimeMillis()
    if (last != null && !force) {
        if (last.contentHash == hash) return
        if (now - last.savedAt < HISTORY_MIN_INTERVAL_MS) return
    }
    dao.insertHistory(
        NoteHistory(
            noteId = before.id,
            savedAt = now,
            title = before.title,
            content = before.content,
            color = before.color,
            createdAt = before.createdAt,
            tags = before.tags,
            folder = before.folder,
            isPinned = before.isPinned,
            isLocked = before.isLocked,
            isChecklist = before.isChecklist,
            reminderTime = before.reminderTime,
            recurrence = before.recurrence,
            contentHash = hash
        )
    )
    dao.trimHistory(before.id, HISTORY_LIMIT)
}
