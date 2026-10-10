package com.zqr.freenote.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Note::class, NoteHistory::class, SyncOutboxEntry::class, SyncMetaEntry::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        /** v1 → v2：便签增加 folder 列（协议 v5，嵌套文件夹路径）。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 → v3：便签增加 recurrence 列（协议 v6，重复提醒规则）。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN recurrence TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3 → v4：新增 note_history 历史版本表（仅本机，不参与同步）。 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS note_history (
                        historyId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        noteId TEXT NOT NULL,
                        savedAt INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        color INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        tags TEXT NOT NULL,
                        folder TEXT NOT NULL,
                        isPinned INTEGER NOT NULL,
                        isLocked INTEGER NOT NULL,
                        isChecklist INTEGER NOT NULL,
                        reminderTime INTEGER,
                        recurrence TEXT NOT NULL,
                        contentHash TEXT NOT NULL
                    )
                    """
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_note_history_noteId_savedAt " +
                        "ON note_history (noteId, savedAt)"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "freenote_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
