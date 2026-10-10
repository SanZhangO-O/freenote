"""SQLite 存储层。

表结构与 Android Room 的 `notes` 表保持一致（含 camelCase 列名），
因此可以直接打开 Android 版导出的 notes.db 副本。另有两张同步辅助表：
`sync_outbox`（待对外传播的本地变更/墓碑）与 `sync_meta`（同步状态等）。

P2P 同步只需要两样东西：
- `sync_entries()`：全部同步条目（id / updatedAt / isDeleted），含尚未物化的墓碑；
- 便签正文按 id 读取或写入（`get` / `apply_remote_lww`）。
"""

from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
from pathlib import Path

from .models import (
    Note,
    folder_ancestors,
    folder_parent,
    folder_sort_key,
    normalize_folder,
    now_ms,
    tombstone_note,
)

_COLUMNS = (
    "id, title, content, color, createdAt, updatedAt, isPinned, isDeleted, deletedAt, "
    "tags, reminderTime, isLocked, isChecklist, folder, recurrence"
)

# 历史版本：每条便签最多保留 30 个版本；距上次记录不足 5 分钟的连续保存合并跳过
HISTORY_LIMIT = 30
HISTORY_MIN_INTERVAL_MS = 5 * 60 * 1000

_SCHEMA = """
CREATE TABLE IF NOT EXISTS notes (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    color INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    isPinned INTEGER NOT NULL,
    isDeleted INTEGER NOT NULL,
    deletedAt INTEGER,
    tags TEXT NOT NULL,
    reminderTime INTEGER,
    isLocked INTEGER NOT NULL,
    isChecklist INTEGER NOT NULL,
    folder TEXT NOT NULL DEFAULT '',
    recurrence TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS index_notes_isDeleted_isPinned_updatedAt
    ON notes (isDeleted, isPinned, updatedAt);
CREATE TABLE IF NOT EXISTS sync_outbox (
    note_id TEXT NOT NULL PRIMARY KEY,
    deleted INTEGER NOT NULL DEFAULT 0,
    queued_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sync_meta (
    key TEXT NOT NULL PRIMARY KEY,
    value TEXT
);
CREATE TABLE IF NOT EXISTS note_history (
    history_id INTEGER PRIMARY KEY AUTOINCREMENT,
    note_id TEXT NOT NULL,
    saved_at INTEGER NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL DEFAULT '',
    color INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    tags TEXT NOT NULL DEFAULT '',
    folder TEXT NOT NULL DEFAULT '',
    is_pinned INTEGER NOT NULL DEFAULT 0,
    is_locked INTEGER NOT NULL DEFAULT 0,
    is_checklist INTEGER NOT NULL DEFAULT 0,
    reminder_time INTEGER,
    recurrence TEXT NOT NULL DEFAULT '',
    content_hash TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS index_note_history_note
    ON note_history (note_id, saved_at DESC);
"""


def escape_like(value: str) -> str:
    """转义 LIKE 通配符，配合 ESCAPE '\\' 使用（与 Android SqlLike 一致）。"""
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _row_to_note(row: sqlite3.Row) -> Note:
    return Note(
        id=row["id"],
        title=row["title"] or "",
        content=row["content"] or "",
        color=row["color"],
        created_at=row["createdAt"],
        updated_at=row["updatedAt"],
        is_pinned=bool(row["isPinned"]),
        is_deleted=bool(row["isDeleted"]),
        deleted_at=row["deletedAt"],
        tags=row["tags"] or "",
        reminder_time=row["reminderTime"],
        is_locked=bool(row["isLocked"]),
        is_checklist=bool(row["isChecklist"]),
        folder=row["folder"] or "",
        recurrence=row["recurrence"] or "",
    )


class NoteStore:
    def __init__(self, path: str | Path):
        self.path = str(path)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA synchronous=NORMAL")
        with self._lock:
            self._conn.executescript(_SCHEMA)
            self._migrate_locked()
            self._conn.commit()

    def _migrate_locked(self) -> None:
        """老版本 notes.db 迁移：补上 folder 列（协议 v5）与 recurrence 列（重复提醒）。"""
        columns = {
            row["name"] for row in self._conn.execute("PRAGMA table_info(notes)").fetchall()
        }
        if "folder" not in columns:
            self._conn.execute("ALTER TABLE notes ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
        if "recurrence" not in columns:
            self._conn.execute("ALTER TABLE notes ADD COLUMN recurrence TEXT NOT NULL DEFAULT ''")

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # ------------------------------------------------------------------ 查询

    def get(self, note_id: str) -> Note | None:
        with self._lock:
            row = self._conn.execute(
                f"SELECT {_COLUMNS} FROM notes WHERE id = ?", (note_id,)
            ).fetchone()
        return _row_to_note(row) if row else None

    def list_active(self) -> list[Note]:
        with self._lock:
            rows = self._conn.execute(
                f"SELECT {_COLUMNS} FROM notes WHERE isDeleted = 0 "
                "ORDER BY isPinned DESC, updatedAt DESC"
            ).fetchall()
        return [_row_to_note(r) for r in rows]

    def list_trash(self) -> list[Note]:
        with self._lock:
            rows = self._conn.execute(
                f"SELECT {_COLUMNS} FROM notes WHERE isDeleted = 1 ORDER BY deletedAt DESC"
            ).fetchall()
        return [_row_to_note(r) for r in rows]

    def search(self, query: str) -> list[Note]:
        escaped = escape_like(query.strip())
        with self._lock:
            rows = self._conn.execute(
                f"""SELECT {_COLUMNS} FROM notes
                    WHERE isDeleted = 0
                      AND (title LIKE '%' || :q || '%' ESCAPE '\\'
                           OR content LIKE '%' || :q || '%' ESCAPE '\\'
                           OR tags LIKE '%' || :q || '%' ESCAPE '\\')
                    ORDER BY isPinned DESC, updatedAt DESC""",
                {"q": escaped},
            ).fetchall()
        return [_row_to_note(r) for r in rows]

    def notes_by_tag(self, tag: str) -> list[Note]:
        escaped = escape_like(tag)
        with self._lock:
            rows = self._conn.execute(
                f"""SELECT {_COLUMNS} FROM notes
                    WHERE isDeleted = 0
                      AND (tags = :tag
                           OR tags LIKE :esc || ',%' ESCAPE '\\'
                           OR tags LIKE '%,' || :esc || ',%' ESCAPE '\\'
                           OR tags LIKE '%,' || :esc ESCAPE '\\')
                    ORDER BY isPinned DESC, updatedAt DESC""",
                {"tag": tag, "esc": escaped},
            ).fetchall()
        return [_row_to_note(r) for r in rows]

    def all_tags(self) -> list[str]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT tags FROM notes WHERE isDeleted = 0 AND tags != ''"
            ).fetchall()
        tags: set[str] = set()
        for row in rows:
            for tag in (row["tags"] or "").split(","):
                tag = tag.strip()
                if tag:
                    tags.add(tag)
        return sorted(tags, key=str.casefold)

    # ------------------------------------------------------------------ 文件夹

    def notes_in_folder(self, folder: str) -> list[Note]:
        """folder 为空串时取未分类；否则取该文件夹及其全部子文件夹下的便签。"""
        with self._lock:
            if folder:
                rows = self._conn.execute(
                    f"""SELECT {_COLUMNS} FROM notes
                        WHERE isDeleted = 0
                          AND (folder = :folder OR folder LIKE :prefix ESCAPE '\\')
                        ORDER BY isPinned DESC, updatedAt DESC""",
                    {"folder": folder, "prefix": escape_like(folder) + "/%"},
                ).fetchall()
            else:
                rows = self._conn.execute(
                    f"SELECT {_COLUMNS} FROM notes WHERE isDeleted = 0 AND folder = '' "
                    "ORDER BY isPinned DESC, updatedAt DESC"
                ).fetchall()
        return [_row_to_note(r) for r in rows]

    def all_folders(self) -> list[str]:
        """活跃便签中出现的全部文件夹路径（含隐式父级），按目录序排序。"""
        with self._lock:
            rows = self._conn.execute(
                "SELECT folder FROM notes WHERE isDeleted = 0 AND folder != ''"
            ).fetchall()
        folders: set[str] = set()
        for row in rows:
            folders.update(folder_ancestors(row["folder"]))
        return sorted(folders, key=folder_sort_key)

    def folder_note_count(self, folder: str) -> int:
        with self._lock:
            row = self._conn.execute(
                """SELECT COUNT(*) AS c FROM notes
                   WHERE isDeleted = 0
                     AND (folder = :folder OR folder LIKE :prefix ESCAPE '\\')""",
                {"folder": folder, "prefix": escape_like(folder) + "/%"},
            ).fetchone()
        return int(row["c"])

    def rename_folder(self, old: str, new: str) -> int:
        """把 old 及其子树的路径前缀改写为 new；返回受影响的便签数。

        new 为空串表示删除文件夹：其下（含子文件夹）便签上移到父级，绝不删除便签。
        """
        old = normalize_folder(old)
        new = normalize_folder(new)
        if not old or old == new:
            return 0
        with self._lock:
            rows = self._conn.execute(
                """SELECT id, folder FROM notes
                   WHERE folder = :old OR folder LIKE :prefix ESCAPE '\\'""",
                {"old": old, "prefix": escape_like(old) + "/%"},
            ).fetchall()
            if not rows:
                return 0
            stamp = now_ms()
            updates: list[tuple[str, int, str]] = []
            for row in rows:
                suffix = row["folder"][len(old):]
                target = (new + suffix) if new else suffix.lstrip("/")
                updates.append((target, stamp, row["id"]))
                self._enqueue_locked(row["id"], False)
            self._conn.executemany(
                "UPDATE notes SET folder = ?, updatedAt = ? WHERE id = ?", updates
            )
            self._conn.commit()
        return len(rows)

    def delete_folder(self, folder: str) -> int:
        """删除文件夹：其下（含子文件夹）便签上移到父级，绝不删除便签。"""
        folder = normalize_folder(folder)
        if not folder:
            return 0
        return self.rename_folder(folder, folder_parent(folder))

    def set_note_folder(self, note_id: str, folder: str) -> None:
        with self._lock:
            self._conn.execute(
                "UPDATE notes SET folder = ?, updatedAt = ? WHERE id = ?",
                (normalize_folder(folder), now_ms(), note_id),
            )
            self._enqueue_locked(note_id, False)
            self._conn.commit()

    def local_folders(self) -> list[str]:
        """本机新建的空文件夹（仅本地，不参与同步），用于文件夹树占位。"""
        raw = self.meta_get("folders.local")
        if not raw:
            return []
        try:
            values = json.loads(raw)
        except ValueError:
            return []
        if not isinstance(values, list):
            return []
        folders: list[str] = []
        for value in values:
            folder = normalize_folder(str(value or ""))
            if folder and folder not in folders:
                folders.append(folder)
        return folders

    def set_local_folders(self, folders: list[str]) -> None:
        cleaned: list[str] = []
        for value in folders:
            folder = normalize_folder(value)
            if folder and folder not in cleaned:
                cleaned.append(folder)
        self.meta_set("folders.local", json.dumps(cleaned, ensure_ascii=False))

    def count_locked(self) -> int:
        with self._lock:
            row = self._conn.execute("SELECT COUNT(*) AS c FROM notes WHERE isLocked = 1").fetchone()
        return int(row["c"])

    def locked_notes(self) -> list[Note]:
        with self._lock:
            rows = self._conn.execute(
                f"SELECT {_COLUMNS} FROM notes WHERE isLocked = 1"
            ).fetchall()
        return [_row_to_note(r) for r in rows]

    def due_reminders(self, now: int | None = None) -> list[Note]:
        now = now if now is not None else now_ms()
        with self._lock:
            rows = self._conn.execute(
                f"SELECT {_COLUMNS} FROM notes "
                "WHERE isDeleted = 0 AND reminderTime IS NOT NULL AND reminderTime > 0 "
                "AND reminderTime <= ?",
                (now,),
            ).fetchall()
        return [_row_to_note(r) for r in rows]

    def trash_expired(self, before: int) -> list[Note]:
        with self._lock:
            rows = self._conn.execute(
                f"SELECT {_COLUMNS} FROM notes WHERE isDeleted = 1 AND deletedAt < ?",
                (before,),
            ).fetchall()
        return [_row_to_note(r) for r in rows]

    # ------------------------------------------------------------------ 写入

    def _upsert_row(self, note: Note) -> None:
        self._conn.execute(
            """INSERT INTO notes (id, title, content, color, createdAt, updatedAt,
                                  isPinned, isDeleted, deletedAt, tags, reminderTime,
                                  isLocked, isChecklist, folder, recurrence)
               VALUES (:id, :title, :content, :color, :createdAt, :updatedAt,
                       :isPinned, :isDeleted, :deletedAt, :tags, :reminderTime,
                       :isLocked, :isChecklist, :folder, :recurrence)
               ON CONFLICT(id) DO UPDATE SET
                   title=excluded.title, content=excluded.content, color=excluded.color,
                   createdAt=excluded.createdAt, updatedAt=excluded.updatedAt,
                   isPinned=excluded.isPinned, isDeleted=excluded.isDeleted,
                   deletedAt=excluded.deletedAt, tags=excluded.tags,
                   reminderTime=excluded.reminderTime, isLocked=excluded.isLocked,
                   isChecklist=excluded.isChecklist, folder=excluded.folder,
                   recurrence=excluded.recurrence""",
            {
                "id": note.id,
                "title": note.title,
                "content": note.content,
                "color": note.color,
                "createdAt": note.created_at,
                "updatedAt": note.updated_at,
                "isPinned": int(note.is_pinned),
                "isDeleted": int(note.is_deleted),
                "deletedAt": note.deleted_at,
                "tags": note.tags,
                "reminderTime": note.reminder_time,
                "isLocked": int(note.is_locked),
                "isChecklist": int(note.is_checklist),
                "folder": note.folder,
                "recurrence": note.recurrence,
            },
        )

    def save(self, note: Note, enqueue: bool = True) -> Note:
        """插入或更新（自动刷新 updatedAt），并记入待传播队列。"""
        with self._lock:
            existing = self._conn.execute(
                f"SELECT {_COLUMNS} FROM notes WHERE id = ?", (note.id,)
            ).fetchone()
            if existing is not None:
                # 覆盖前把旧版本写入历史（同内容/5 分钟内合并跳过，最多 30 版）
                self._record_history_locked(_row_to_note(existing))
            note.updated_at = now_ms()
            self._upsert_row(note)
            if enqueue:
                self._enqueue_locked(note.id, False)
            self._conn.commit()
        return note

    def apply_remote(self, note: Note) -> None:
        """同步写入：保留远端 updatedAt，不产生新的待传播变更。"""
        with self._lock:
            existing = self._conn.execute(
                f"SELECT {_COLUMNS} FROM notes WHERE id = ?", (note.id,)
            ).fetchone()
            if existing is not None:
                self._record_history_locked(_row_to_note(existing))
            self._upsert_row(note)
            self._conn.commit()

    def apply_remote_lww(self, note: Note) -> bool:
        """按最后写入者获胜应用远端便签；返回是否被采纳。

        同毫秒平局规则（协议 v5，与 incoming_wins 一致）：
        删除优先；时间戳与删除标记都相同时内容哈希小者胜。
        """
        with self._lock:
            local = self.get(note.id)
            if local is None:
                self._upsert_row(note)
                self._conn.commit()
                return True
            if note.updated_at != local.updated_at:
                wins = note.updated_at > local.updated_at
            elif note.is_deleted != local.is_deleted:
                wins = note.is_deleted
            else:
                incoming_hash = note.note_content_hash()
                local_hash = local.note_content_hash()
                wins = bool(incoming_hash) and bool(local_hash) and incoming_hash < local_hash
            if wins:
                self._record_history_locked(local)
                self._upsert_row(note)
                self._conn.commit()
            return wins

    # ------------------------------------------------------------------ 历史版本

    def _record_history_locked(self, note: Note, force: bool = False) -> None:
        """记录一个历史版本；同内容跳过，5 分钟内的连续保存合并跳过（force 除外），超限裁剪最旧。"""
        hash_value = note.note_content_hash()
        last = self._conn.execute(
            "SELECT saved_at, content_hash FROM note_history WHERE note_id = ? "
            "ORDER BY saved_at DESC, history_id DESC LIMIT 1",
            (note.id,),
        ).fetchone()
        now = now_ms()
        if last is not None and not force:
            if last["content_hash"] == hash_value:
                return
            if now - last["saved_at"] < HISTORY_MIN_INTERVAL_MS:
                return
        self._conn.execute(
            """INSERT INTO note_history (note_id, saved_at, title, content, color, created_at,
                                          tags, folder, is_pinned, is_locked, is_checklist,
                                          reminder_time, recurrence, content_hash)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                note.id,
                now,
                note.title,
                note.content,
                note.color,
                note.created_at,
                note.tags,
                note.folder,
                int(note.is_pinned),
                int(note.is_locked),
                int(note.is_checklist),
                note.reminder_time,
                note.recurrence,
                hash_value,
            ),
        )
        self._conn.execute(
            "DELETE FROM note_history WHERE note_id = ? AND history_id NOT IN "
            "(SELECT history_id FROM note_history WHERE note_id = ? "
            " ORDER BY saved_at DESC, history_id DESC LIMIT ?)",
            (note.id, note.id, HISTORY_LIMIT),
        )

    def history_versions(self, note_id: str) -> list[sqlite3.Row]:
        """历史版本列表（新→旧）。"""
        with self._lock:
            return self._conn.execute(
                "SELECT * FROM note_history WHERE note_id = ? "
                "ORDER BY saved_at DESC, history_id DESC",
                (note_id,),
            ).fetchall()

    def restore_history(self, note_id: str, history_id: int) -> bool:
        """恢复某个历史版本为当前内容（先把当前状态记入历史，可再恢复回来）。"""
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM note_history WHERE history_id = ? AND note_id = ?",
                (history_id, note_id),
            ).fetchone()
            current = self._conn.execute(
                f"SELECT {_COLUMNS} FROM notes WHERE id = ?", (note_id,)
            ).fetchone()
            if row is None or current is None:
                return False
            # 恢复前无条件记录当前状态：让「恢复」本身也可再撤销
            self._record_history_locked(_row_to_note(current), force=True)
            self._conn.execute(
                """UPDATE notes SET title = ?, content = ?, color = ?, tags = ?, folder = ?,
                                    isPinned = ?, isLocked = ?, isChecklist = ?,
                                    reminderTime = ?, recurrence = ?, updatedAt = ?
                   WHERE id = ?""",
                (
                    row["title"],
                    row["content"],
                    row["color"],
                    row["tags"],
                    row["folder"],
                    int(row["is_pinned"]),
                    int(row["is_locked"]),
                    int(row["is_checklist"]),
                    row["reminder_time"],
                    row["recurrence"],
                    now_ms(),
                    note_id,
                ),
            )
            self._enqueue_locked(note_id, False)
            self._conn.commit()
        return True

    def delete_hard(self, note_id: str, enqueue: bool = True) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM notes WHERE id = ?", (note_id,))
            self._conn.execute("DELETE FROM note_history WHERE note_id = ?", (note_id,))
            if enqueue:
                self._enqueue_locked(note_id, True)
            self._conn.commit()

    def move_to_trash(self, note_id: str) -> None:
        with self._lock:
            stamp = now_ms()
            self._conn.execute(
                "UPDATE notes SET isDeleted = 1, deletedAt = ?, reminderTime = NULL, "
                "updatedAt = ? WHERE id = ?",
                (stamp, stamp, note_id),
            )
            self._enqueue_locked(note_id, False, stamp)
            self._conn.commit()

    def restore(self, note_id: str) -> None:
        with self._lock:
            self._conn.execute(
                "UPDATE notes SET isDeleted = 0, deletedAt = NULL, updatedAt = ? WHERE id = ?",
                (now_ms(), note_id),
            )
            self._enqueue_locked(note_id, False)
            self._conn.commit()

    def set_pinned(self, note_id: str, pinned: bool) -> None:
        with self._lock:
            self._conn.execute(
                "UPDATE notes SET isPinned = ?, updatedAt = ? WHERE id = ?",
                (int(pinned), now_ms(), note_id),
            )
            self._enqueue_locked(note_id, False)
            self._conn.commit()

    def set_reminder(self, note_id: str, reminder_time: int | None, touch: bool = True) -> None:
        with self._lock:
            if touch:
                self._conn.execute(
                    "UPDATE notes SET reminderTime = ?, updatedAt = ? WHERE id = ?",
                    (reminder_time, now_ms(), note_id),
                )
                self._enqueue_locked(note_id, False)
            else:
                self._conn.execute(
                    "UPDATE notes SET reminderTime = ? WHERE id = ?",
                    (reminder_time, note_id),
                )
            self._conn.commit()

    # ------------------------------------------------------------------ P2P 同步辅助

    def _enqueue_locked(self, note_id: str, deleted: bool, at_ms: int | None = None) -> None:
        self._conn.execute(
            """INSERT INTO sync_outbox (note_id, deleted, queued_at) VALUES (?, ?, ?)
               ON CONFLICT(note_id) DO UPDATE SET
                   deleted = CASE WHEN excluded.deleted = 1 THEN 1 ELSE sync_outbox.deleted END,
                   queued_at = excluded.queued_at""",
            (note_id, int(deleted), at_ms if at_ms else now_ms()),
        )

    def enqueue_sync(self, note_id: str, deleted: bool = False) -> None:
        with self._lock:
            self._enqueue_locked(note_id, deleted)
            self._conn.commit()

    def outbox(self) -> list[tuple[str, bool, int]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT note_id, deleted, queued_at FROM sync_outbox ORDER BY queued_at"
            ).fetchall()
        return [(r["note_id"], bool(r["deleted"]), int(r["queued_at"])) for r in rows]

    def outbox_clear(self, entries: list[tuple[str, int]]) -> None:
        """按 note_id + queued_at 精确清除已传播的队列项（并发修改自动保留）。"""
        if not entries:
            return
        with self._lock:
            self._conn.executemany(
                "DELETE FROM sync_outbox WHERE note_id = ? AND queued_at = ?", entries
            )
            self._conn.commit()

    def tombstones(self) -> list[tuple[str, int]]:
        """已硬删除、但尚未物化为删除行的墓碑：(note_id, deletedAt)。

        与 FreeNote 服务端一致：墓碑以“已删除便签”的形式继续存在，
        才能让 LWW 比较与摘要保持一致。
        """
        with self._lock:
            rows = self._conn.execute(
                """SELECT o.note_id AS note_id, o.queued_at AS queued_at
                   FROM sync_outbox o LEFT JOIN notes n ON n.id = o.note_id
                   WHERE n.id IS NULL AND o.deleted = 1"""
            ).fetchall()
        return [(r["note_id"], int(r["queued_at"])) for r in rows]

    def materialize_tombstone(self, note_id: str, deleted_at: int) -> None:
        """把墓碑写成正式的删除行（保留 updatedAt=deletedAt），并清除队列项。"""
        with self._lock:
            if self._conn.execute(
                "SELECT id FROM notes WHERE id = ?", (note_id,)
            ).fetchone() is None:
                from .models import tombstone_note

                self._upsert_row(tombstone_note(note_id, deleted_at))
            self._conn.execute("DELETE FROM sync_outbox WHERE note_id = ?", (note_id,))
            self._conn.commit()

    def sync_entries(self) -> list[tuple[str, int, bool, str]]:
        """全部同步条目 (id, updatedAt, isDeleted, contentHash)，按 id 排序。

        包含 notes 表的全部行，以及尚未物化的硬删除墓碑。
        排序按 Unicode 码点（等价于 UTF-8 字节序），与 Android 端一致。
        """
        with self._lock:
            rows = self._conn.execute(
                f"SELECT {_COLUMNS} FROM notes"
            ).fetchall()
            entries: list[tuple[str, int, bool, str]] = []
            for row in rows:
                note = _row_to_note(row)
                entries.append(
                    (note.id, int(note.updated_at), bool(note.is_deleted), note.note_content_hash())
                )
        known = {item[0] for item in entries}
        for note_id, deleted_at in self.tombstones():
            if note_id not in known:
                tomb = tombstone_note(note_id, deleted_at)
                entries.append((note_id, int(deleted_at), True, tomb.note_content_hash()))
        entries.sort(key=lambda item: item[0])
        return entries

    def state_digest(self) -> str:
        """同步状态摘要：两端完全一致时摘要相同，用于快速跳过同步。

        内容哈希参与摘要：同毫秒不同内容的分歧也能被发现并触发增量交换。
        协议 v6 起附件引用状态（引用名 → 本地是否存在）同样参与摘要，
        这样「便签一致但图片缺失」的设备也会被发现并触发附件补传。
        """
        from . import attachments

        digest = hashlib.sha256()
        for note_id, updated_at, deleted, content_hash in self.sync_entries():
            digest.update(
                f"{note_id}:{updated_at}:{int(deleted)}:{content_hash}\n".encode("utf-8")
            )
        referenced, present = attachments.referenced_names(self)
        digest.update(b"\x1e")
        for name in sorted(referenced):
            digest.update(f"{name}:{1 if name in present else 0}\n".encode("utf-8"))
        return digest.hexdigest()

    def meta_get(self, key: str, default: str | None = None) -> str | None:
        with self._lock:
            row = self._conn.execute("SELECT value FROM sync_meta WHERE key = ?", (key,)).fetchone()
        return row["value"] if row else default

    def find_by_title(self, title: str) -> str | None:
        """按标题查找未删除便签的 id（[[双向链接]] 跳转用；多个同名取最近修改的）。"""
        clean = (title or "").strip()
        if not clean:
            return None
        with self._lock:
            row = self._conn.execute(
                "SELECT id FROM notes WHERE isDeleted = 0 AND title = ? "
                "ORDER BY updatedAt DESC, rowid DESC LIMIT 1",
                (clean,),
            ).fetchone()
        return str(row["id"]) if row else None

    def meta_set(self, key: str, value: str) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO sync_meta (key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, value),
            )
            self._conn.commit()

    # ------------------------------------------------------------------ 回收站

    def purge_expired(self, before: int) -> list[Note]:
        """删除过期回收站便签并返回它们，供调用方清理图片文件。

        墓碑沿用原删除时间（而不是清理时刻），保证 LWW 语义与摘要稳定：
        离线很久的设备回来时，删除与编辑的先后关系不会因为 30 天清理而改变。
        """
        expired = self.trash_expired(before)
        if not expired:
            return []
        with self._lock:
            self._conn.execute("DELETE FROM notes WHERE isDeleted = 1 AND deletedAt < ?", (before,))
            for note in expired:
                self._conn.execute("DELETE FROM note_history WHERE note_id = ?", (note.id,))
                self._enqueue_locked(note.id, True, note.deleted_at)
            self._conn.commit()
        return expired

    def empty_trash(self) -> list[Note]:
        trashed = self.list_trash()
        if not trashed:
            return []
        with self._lock:
            self._conn.execute("DELETE FROM notes WHERE isDeleted = 1")
            for note in trashed:
                self._conn.execute("DELETE FROM note_history WHERE note_id = ?", (note.id,))
                self._enqueue_locked(note.id, True, note.deleted_at)
            self._conn.commit()
        return trashed
