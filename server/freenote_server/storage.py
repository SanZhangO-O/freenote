"""SQLite 存储层：用户、令牌、便签（含同步序号）。

每个用户有一个单调递增的同步序号 seq；每条便签记录最后一次变更时的 seq。
客户端用 sinceSeq 拉取增量，服务端无需保存操作日志。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import re
import secrets
import sqlite3
import threading
import time
from pathlib import Path

# OWASP 建议：PBKDF2-HMAC-SHA256 至少 600000 次迭代。
# 存储格式自带迭代数（iterations:salt:hash），旧哈希仍可按原迭代数校验。
PASSWORD_ITERATIONS = 600_000
PASSWORD_SALT_BYTES = 16
DEFAULT_COLOR = 0xFFFFF9C4

# 令牌有效期（毫秒）：默认 90 天；旧令牌（无过期时间）保持有效直到下次登录换发。
TOKEN_TTL_MS = 90 * 24 * 60 * 60 * 1000

# 附件（协议 v6）：按内容寻址存储；单文件 8MB，单用户配额 512MB。
ATTACHMENT_MAX_BYTES = 8 * 1024 * 1024
ATTACHMENT_BYTES_PER_MESSAGE = 8 * 1024 * 1024
ATTACHMENTS_PER_MESSAGE = 8
ATTACHMENT_USER_QUOTA_BYTES = 512 * 1024 * 1024
_ATTACHMENT_NAME_RE = re.compile(r"^[0-9a-f]{16}\.[a-z0-9]{1,8}$")


def valid_attachment_name(name: str) -> bool:
    return bool(name) and bool(_ATTACHMENT_NAME_RE.match(name))

_SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS tokens (
    token TEXT NOT NULL PRIMARY KEY,
    user_id INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER
);
CREATE TABLE IF NOT EXISTS notes (
    id TEXT NOT NULL,
    user_id INTEGER NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL DEFAULT '',
    color INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    isPinned INTEGER NOT NULL DEFAULT 0,
    isDeleted INTEGER NOT NULL DEFAULT 0,
    deletedAt INTEGER,
    tags TEXT NOT NULL DEFAULT '',
    reminderTime INTEGER,
    isLocked INTEGER NOT NULL DEFAULT 0,
    isChecklist INTEGER NOT NULL DEFAULT 0,
    folder TEXT NOT NULL DEFAULT '',
    recurrence TEXT NOT NULL DEFAULT '',
    seq INTEGER NOT NULL,
    PRIMARY KEY (user_id, id)
);
CREATE INDEX IF NOT EXISTS idx_notes_user_seq ON notes (user_id, seq);
CREATE TABLE IF NOT EXISTS user_state (
    user_id INTEGER NOT NULL PRIMARY KEY,
    seq INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS attachments (
    user_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    size INTEGER NOT NULL,
    data BLOB NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, name)
);
"""

_NOTE_COLUMNS = (
    "id, title, content, color, createdAt, updatedAt, isPinned, isDeleted, deletedAt, "
    "tags, reminderTime, isLocked, isChecklist, folder, recurrence, seq"
)


def now_ms() -> int:
    return int(time.time() * 1000)


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(PASSWORD_SALT_BYTES)
    derived = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PASSWORD_ITERATIONS, 32)
    return f"{PASSWORD_ITERATIONS}:{_b64(salt)}:{_b64(derived)}"


def verify_password(password: str, stored: str) -> bool:
    try:
        iterations_text, salt_b64, expected_b64 = stored.split(":")
        iterations = int(iterations_text)
        salt = base64.b64decode(salt_b64)
        expected = base64.b64decode(expected_b64)
    except Exception:  # noqa: BLE001
        return False
    derived = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations, 32)
    return hmac.compare_digest(derived, expected)


def _token_hash(token: str) -> str:
    """令牌在库中只存 SHA-256：数据库泄露时无法直接冒用会话。"""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _as_int(value, default=None):
    try:
        return int(value)
    except (TypeError, ValueError, OverflowError):
        return default


_INT64_MIN = -(2**63)
_INT64_MAX = 2**63 - 1

_ID_MAX_LENGTH = 128
_TITLE_MAX_LENGTH = 1000
_CONTENT_MAX_BYTES = 512 * 1024
_TAGS_MAX_COUNT = 50
_TAG_MAX_LENGTH = 100
_FOLDER_MAX_LENGTH = 200


def _in_int64(value: int) -> bool:
    return _INT64_MIN <= value <= _INT64_MAX


def _valid_id(note_id: str) -> bool:
    if not note_id or len(note_id) > _ID_MAX_LENGTH:
        return False
    return all(ord(ch) >= 0x20 and ord(ch) != 0x7F and ch != ":" for ch in note_id)


def content_hash_fields(
    *,
    title: str,
    content: str,
    tags: str,
    folder: str,
    color: int,
    created_at: int,
    is_pinned: bool,
    is_locked: bool,
    is_checklist: bool,
    reminder_time: int | None,
    deleted_at: int | None,
) -> str:
    """内容指纹（协议 v5）：同毫秒平局的确定性仲裁依据。

    规则与客户端（freenote/windows models.py、android SyncProtocol.kt）一致：
    除 id/updatedAt 外的全部字段按固定顺序以 \\x1f 连接，SHA-256 取前 16 位十六进制；
    可空时间戳以 -1 表示，布尔以 1/0 表示。
    """
    canonical = "\x1f".join(
        (
            title,
            content,
            tags,
            folder,
            str(color),
            str(created_at),
            "1" if is_pinned else "0",
            "1" if is_locked else "0",
            "1" if is_checklist else "0",
            str(reminder_time if reminder_time is not None else -1),
            str(deleted_at if deleted_at is not None else -1),
        )
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]


def _row_hash(row: sqlite3.Row) -> str:
    return content_hash_fields(
        title=row["title"],
        content=row["content"],
        tags=row["tags"],
        folder=row["folder"],
        color=row["color"],
        created_at=row["createdAt"],
        is_pinned=bool(row["isPinned"]),
        is_locked=bool(row["isLocked"]),
        is_checklist=bool(row["isChecklist"]),
        reminder_time=row["reminderTime"],
        deleted_at=row["deletedAt"],
    )


def parse_note(payload: dict) -> dict | None:
    """把客户端 camelCase 便签对象规范化为行字典（不解释加密内容）。

    updatedAt 缺失/非法、id 非法、整数超界或字段超长的条目返回 None（视为无效，不入库），
    避免畸形数据以服务器时间伪造 LWW 或破坏入库。
    """
    updated = _as_int(payload.get("updatedAt"))
    if updated is None or not _in_int64(updated):
        return None
    created = _as_int(payload.get("createdAt"), now_ms())
    reminder = _as_int(payload.get("reminderTime"))
    deleted_at = _as_int(payload.get("deletedAt"))
    # color 允许 0（黑色）；仅缺失/非法时回落默认色
    color = _as_int(payload.get("color"))
    if not all(
        value is None or _in_int64(value) for value in (created, color, reminder, deleted_at)
    ):
        return None
    note_id = str(payload.get("id") or "")
    title = str(payload.get("title") or "")
    content = str(payload.get("content") or "")
    tags = str(payload.get("tags") or "")
    folder = str(payload.get("folder") or "")[:_FOLDER_MAX_LENGTH]
    recurrence_raw = str(payload.get("recurrence") or "").strip().lower()
    recurrence = recurrence_raw if recurrence_raw in ("daily", "weekly", "monthly") else ""
    tag_items = tags.split(",")
    if (
        not _valid_id(note_id)
        or len(title) > _TITLE_MAX_LENGTH
        or len(content.encode("utf-8")) > _CONTENT_MAX_BYTES
        or len(tag_items) > _TAGS_MAX_COUNT
        or any(len(tag) > _TAG_MAX_LENGTH for tag in tag_items)
    ):
        return None
    row = {
        "id": note_id,
        "title": title,
        "content": content,
        "color": color if color is not None else DEFAULT_COLOR,
        "createdAt": created,
        "updatedAt": updated,
        "isPinned": 1 if payload.get("isPinned") else 0,
        "isDeleted": 1 if payload.get("isDeleted") else 0,
        "deletedAt": deleted_at if deleted_at and deleted_at > 0 else None,
        "tags": tags,
        "reminderTime": reminder if reminder and reminder > 0 else None,
        "isLocked": 1 if payload.get("isLocked") else 0,
        "isChecklist": 1 if payload.get("isChecklist") else 0,
        "folder": folder,
        "recurrence": recurrence,
    }
    row["contentHash"] = content_hash_fields(
        title=row["title"],
        content=row["content"],
        tags=row["tags"],
        folder=row["folder"],
        color=row["color"],
        created_at=row["createdAt"],
        is_pinned=bool(row["isPinned"]),
        is_locked=bool(row["isLocked"]),
        is_checklist=bool(row["isChecklist"]),
        reminder_time=row["reminderTime"],
        deleted_at=row["deletedAt"],
    )
    return row


def note_to_wire(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"],
        "title": row["title"],
        "content": row["content"],
        "color": row["color"],
        "createdAt": row["createdAt"],
        "updatedAt": row["updatedAt"],
        "isPinned": bool(row["isPinned"]),
        "isDeleted": bool(row["isDeleted"]),
        "deletedAt": row["deletedAt"],
        "tags": row["tags"],
        "reminderTime": row["reminderTime"],
        "isLocked": bool(row["isLocked"]),
        "isChecklist": bool(row["isChecklist"]),
        "folder": row["folder"],
        "recurrence": row["recurrence"] if "recurrence" in row.keys() else "",
    }


class Storage:
    def __init__(self, path: str | Path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
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
        """老版本服务端库迁移：补上 folder 列（协议 v5）与令牌过期时间列（v6）。"""
        columns = {
            row["name"] for row in self._conn.execute("PRAGMA table_info(notes)").fetchall()
        }
        if "folder" not in columns:
            self._conn.execute("ALTER TABLE notes ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
        if "recurrence" not in columns:
            self._conn.execute("ALTER TABLE notes ADD COLUMN recurrence TEXT NOT NULL DEFAULT ''")
        token_columns = {
            row["name"] for row in self._conn.execute("PRAGMA table_info(tokens)").fetchall()
        }
        if "expires_at" not in token_columns:
            self._conn.execute("ALTER TABLE tokens ADD COLUMN expires_at INTEGER")

    # ------------------------------------------------------------------ 用户

    def create_user(self, username: str, password: str) -> int:
        """创建用户；用户名已存在时抛出 ValueError。"""
        with self._lock:
            if self._conn.execute(
                "SELECT id FROM users WHERE username = ?", (username,)
            ).fetchone():
                raise ValueError("用户名已被注册")
            cursor = self._conn.execute(
                "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
                (username, hash_password(password), now_ms()),
            )
            user_id = int(cursor.lastrowid)
            self._conn.execute(
                "INSERT OR IGNORE INTO user_state (user_id, seq) VALUES (?, 0)", (user_id,)
            )
            self._conn.commit()
        return user_id

    def verify_user(self, username: str, password: str) -> int | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT id, password_hash FROM users WHERE username = ?", (username,)
            ).fetchone()
        if row is None or not verify_password(password, row["password_hash"]):
            return None
        return int(row["id"])

    def change_password(self, user_id: int, old_password: str, new_password: str) -> bool:
        """校验旧密码后改密；成功返回 True，旧密码错误返回 False。"""
        with self._lock:
            row = self._conn.execute(
                "SELECT password_hash FROM users WHERE id = ?", (user_id,)
            ).fetchone()
            if row is None or not verify_password(old_password, row["password_hash"]):
                return False
            self._conn.execute(
                "UPDATE users SET password_hash = ? WHERE id = ?",
                (hash_password(new_password), user_id),
            )
            self._conn.commit()
        return True

    def verify_password_for_user(self, user_id: int, password: str) -> bool:
        with self._lock:
            row = self._conn.execute(
                "SELECT password_hash FROM users WHERE id = ?", (user_id,)
            ).fetchone()
        return bool(row) and verify_password(password, row["password_hash"])

    def delete_user(self, user_id: int) -> None:
        """注销账号：删除该用户的便签、附件、令牌与账户记录。"""
        with self._lock:
            self._conn.execute("DELETE FROM notes WHERE user_id = ?", (user_id,))
            self._conn.execute("DELETE FROM attachments WHERE user_id = ?", (user_id,))
            self._conn.execute("DELETE FROM tokens WHERE user_id = ?", (user_id,))
            self._conn.execute("DELETE FROM user_state WHERE user_id = ?", (user_id,))
            self._conn.execute("DELETE FROM users WHERE id = ?", (user_id,))
            self._conn.commit()

    def admin_overview(self) -> list[dict]:
        """管理面板统计：每个用户的数据规模（不含便签内容）。"""
        with self._lock:
            rows = self._conn.execute(
                """SELECT u.id AS id, u.username AS username, u.created_at AS created_at,
                          (SELECT COUNT(*) FROM tokens t WHERE t.user_id = u.id) AS tokens,
                          (SELECT COUNT(*) FROM notes n
                             WHERE n.user_id = u.id AND n.isDeleted = 0) AS active_notes,
                          (SELECT COUNT(*) FROM notes n WHERE n.user_id = u.id) AS total_notes,
                          (SELECT COUNT(*) FROM attachments a
                             WHERE a.user_id = u.id) AS attachment_count,
                          (SELECT COALESCE(SUM(a.size), 0) FROM attachments a
                             WHERE a.user_id = u.id) AS attachment_bytes
                   FROM users u ORDER BY u.id"""
            ).fetchall()
        return [dict(row) for row in rows]

    # ------------------------------------------------------------------ 令牌

    def create_token(self, user_id: int, ttl_ms: int = TOKEN_TTL_MS) -> str:
        token = secrets.token_urlsafe(32)
        expires_at = now_ms() + ttl_ms if ttl_ms and ttl_ms > 0 else None
        with self._lock:
            self._conn.execute(
                "INSERT INTO tokens (token, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                (_token_hash(token), user_id, now_ms(), expires_at),
            )
            self._conn.commit()
        return token

    def user_by_token(self, token: str) -> int | None:
        if not token:
            return None
        digest = _token_hash(token)
        now = now_ms()
        with self._lock:
            row = self._conn.execute(
                "SELECT user_id, expires_at FROM tokens WHERE token = ?", (digest,)
            ).fetchone()
            if row is None:
                return None
            expires_at = row["expires_at"]
            if expires_at is not None and expires_at <= now:
                # 过期令牌直接清理，客户端会收到 401 并重新登录
                self._conn.execute("DELETE FROM tokens WHERE token = ?", (digest,))
                self._conn.commit()
                return None
        return int(row["user_id"])

    def revoke_token(self, token: str) -> None:
        with self._lock:
            self._conn.execute(
                "DELETE FROM tokens WHERE token = ?", (_token_hash(token),)
            )
            self._conn.commit()

    def revoke_other_tokens(self, user_id: int, keep_token: str) -> int:
        """改密后撤销该用户的其他登录令牌（保留当前会话）；返回撤销数量。"""
        digest = _token_hash(keep_token)
        with self._lock:
            cursor = self._conn.execute(
                "DELETE FROM tokens WHERE user_id = ? AND token != ?", (user_id, digest)
            )
            self._conn.commit()
        return int(cursor.rowcount or 0)

    # ------------------------------------------------------------------ 附件（协议 v6）

    def attachment_total(self, user_id: int) -> int:
        with self._lock:
            row = self._conn.execute(
                "SELECT COALESCE(SUM(size), 0) AS total FROM attachments WHERE user_id = ?",
                (user_id,),
            ).fetchone()
        return int(row["total"]) if row else 0

    def store_attachment(self, user_id: int, name: str, data: bytes) -> bool:
        """写入附件；名称与内容哈希不符/超限返回 False；配额不足抛 ValueError。

        同名附件已存在时直接视为成功（内容寻址天然幂等）。
        """
        if not valid_attachment_name(name) or not data or len(data) > ATTACHMENT_MAX_BYTES:
            return False
        if not name.startswith(hashlib.sha256(data).hexdigest()[:16]):
            return False
        with self._lock:
            if self._conn.execute(
                "SELECT 1 FROM attachments WHERE user_id = ? AND name = ?", (user_id, name)
            ).fetchone():
                return True
            if self.attachment_total(user_id) + len(data) > ATTACHMENT_USER_QUOTA_BYTES:
                raise ValueError("附件空间不足")
            self._conn.execute(
                "INSERT INTO attachments (user_id, name, size, data, created_at) "
                "VALUES (?, ?, ?, ?, ?)",
                (user_id, name, len(data), data, now_ms()),
            )
            self._conn.commit()
        return True

    def attachments_by_names(self, user_id: int, names: list[str]) -> dict[str, bytes]:
        cleaned = [name for name in names if valid_attachment_name(name)]
        if not cleaned:
            return {}
        placeholders = ",".join("?" for _ in cleaned)
        with self._lock:
            rows = self._conn.execute(
                f"SELECT name, data FROM attachments WHERE user_id = ? AND name IN ({placeholders})",
                (user_id, *cleaned),
            ).fetchall()
        return {row["name"]: row["data"] for row in rows}

    def delete_attachments(self, user_id: int) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM attachments WHERE user_id = ?", (user_id,))
            self._conn.commit()

    # ------------------------------------------------------------------ 同步

    def _next_seq(self, user_id: int) -> int:
        self._conn.execute(
            "INSERT OR IGNORE INTO user_state (user_id, seq) VALUES (?, 0)", (user_id,)
        )
        self._conn.execute(
            "UPDATE user_state SET seq = seq + 1 WHERE user_id = ?", (user_id,)
        )
        row = self._conn.execute(
            "SELECT seq FROM user_state WHERE user_id = ?", (user_id,)
        ).fetchone()
        return int(row["seq"])

    def apply_push(
        self, user_id: int, notes: list[dict], tombstones: list[dict]
    ) -> tuple[int, int]:
        """应用客户端推送，返回 (applied, conflicts)。

        LWW 规则（协议 v5，与两端客户端一致）：
        - 时间戳不同：新者胜，更旧的写入计为冲突；
        - 时间戳相同、删除标记不同：删除优先；
        - 时间戳与删除标记都相同：内容哈希小者胜（确定性仲裁），哈希相同视为幂等。
        """
        applied = 0
        conflicts = 0
        with self._lock:
            try:
                for payload in notes:
                    row = parse_note(payload)
                    if row is None:
                        conflicts += 1
                        continue
                    existing = self._conn.execute(
                        """SELECT title, content, tags, folder, color, createdAt, updatedAt, isPinned,
                                  isDeleted, deletedAt, reminderTime, isLocked, isChecklist
                           FROM notes WHERE user_id = ? AND id = ?""",
                        (user_id, row["id"]),
                    ).fetchone()
                    if existing is not None:
                        existing_ts = existing["updatedAt"]
                        existing_deleted = bool(existing["isDeleted"])
                        if row["updatedAt"] < existing_ts:
                            conflicts += 1
                            continue
                        if row["updatedAt"] == existing_ts:
                            if row["isDeleted"] != existing_deleted:
                                # 同毫秒一边编辑一边删除：删除优先
                                if not row["isDeleted"]:
                                    conflicts += 1
                                    continue
                            elif row["isDeleted"] == existing_deleted:
                                existing_hash = _row_hash(existing)
                                if row["contentHash"] >= existing_hash:
                                    # 哈希更大或内容一致：保留服务端版本（幂等/确定性落败）
                                    if row["contentHash"] != existing_hash:
                                        conflicts += 1
                                    continue
                    seq = self._next_seq(user_id)
                    self._conn.execute(
                        f"""INSERT INTO notes (user_id, {_NOTE_COLUMNS})
                            VALUES (:user_id, :id, :title, :content, :color, :createdAt, :updatedAt,
                                    :isPinned, :isDeleted, :deletedAt, :tags, :reminderTime,
                                    :isLocked, :isChecklist, :folder, :recurrence, :seq)
                            ON CONFLICT(user_id, id) DO UPDATE SET
                                title=excluded.title, content=excluded.content, color=excluded.color,
                                createdAt=excluded.createdAt, updatedAt=excluded.updatedAt,
                                isPinned=excluded.isPinned, isDeleted=excluded.isDeleted,
                                deletedAt=excluded.deletedAt, tags=excluded.tags,
                                reminderTime=excluded.reminderTime, isLocked=excluded.isLocked,
                                isChecklist=excluded.isChecklist, folder=excluded.folder,
                                recurrence=excluded.recurrence,
                                seq=excluded.seq""",
                        {**row, "seq": seq, "user_id": user_id},
                    )
                    applied += 1

                for item in tombstones:
                    note_id = str((item or {}).get("id") or "")
                    if not _valid_id(note_id):
                        conflicts += 1
                        continue
                    raw_deleted_at = (item or {}).get("deletedAt")
                    deleted_at = _as_int(raw_deleted_at)
                    if deleted_at is None:
                        if raw_deleted_at is not None:
                            conflicts += 1
                            continue
                        deleted_at = now_ms()
                    elif not _in_int64(deleted_at):
                        conflicts += 1
                        continue
                    deleted_at = deleted_at or now_ms()
                    existing = self._conn.execute(
                        """SELECT title, content, tags, color, createdAt, updatedAt, isPinned,
                                  isDeleted, deletedAt, reminderTime, isLocked, isChecklist
                           FROM notes WHERE user_id = ? AND id = ?""",
                        (user_id, note_id),
                    ).fetchone()
                    if existing is not None:
                        existing_ts = existing["updatedAt"]
                        existing_deleted = bool(existing["isDeleted"])
                        if deleted_at < existing_ts:
                            conflicts += 1
                            continue
                        if deleted_at == existing_ts and existing_deleted:
                            continue  # 已是同刻删除：幂等
                    seq = self._next_seq(user_id)
                    self._conn.execute(
                        """INSERT INTO notes (id, user_id, color, createdAt, updatedAt,
                                              isDeleted, deletedAt, folder, seq)
                           VALUES (?, ?, ?, ?, ?, 1, ?, '', ?)
                           ON CONFLICT(user_id, id) DO UPDATE SET
                               isDeleted = 1, deletedAt = excluded.deletedAt,
                               updatedAt = excluded.updatedAt, folder = '',
                               seq = excluded.seq""",
                        (note_id, user_id, DEFAULT_COLOR, deleted_at, deleted_at, deleted_at, seq),
                    )
                    applied += 1

                self._conn.commit()
            except Exception:
                self._conn.rollback()
                raise
        return applied, conflicts

    def current_seq(self, user_id: int) -> int:
        with self._lock:
            row = self._conn.execute(
                "SELECT seq FROM user_state WHERE user_id = ?", (user_id,)
            ).fetchone()
        return int(row["seq"]) if row else 0

    def changes_since(self, user_id: int, since_seq: int) -> list[sqlite3.Row]:
        with self._lock:
            return self._conn.execute(
                f"SELECT {_NOTE_COLUMNS} FROM notes WHERE user_id = ? AND seq > ? ORDER BY seq",
                (user_id, min(max(0, since_seq), _INT64_MAX)),
            ).fetchall()
