package com.zqr.freenote.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 待同步队列：本地写入的便签 id（含硬删除墓碑）。
 * 与 FreeNote 服务端的 outbox 语义一致：
 * 同一 id 重复入队时删除标记只增不减，queued_at 取最新。
 */
@Entity(tableName = "sync_outbox")
data class SyncOutboxEntry(
    @PrimaryKey @ColumnInfo(name = "note_id") val noteId: String,
    val deleted: Boolean = false,
    @ColumnInfo(name = "queued_at") val queuedAt: Long = System.currentTimeMillis()
)

/** 同步元数据（last_seq、last_sync_at 等）。 */
@Entity(tableName = "sync_meta")
data class SyncMetaEntry(
    @PrimaryKey val key: String,
    val value: String?
)

/** 同步条目：清单/摘要的基本单元（含协议 v5 的内容哈希）。 */
data class SyncEntry(
    val id: String,
    val updatedAt: Long,
    val isDeleted: Boolean,
    val contentHash: String
)

/** 尚未物化为删除行的硬删除墓碑。 */
data class TombstoneRow(
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "queued_at") val queuedAt: Long
)
