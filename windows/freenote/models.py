"""数据模型：与 Android 版 Note 实体一一对应（含 JSON 备份/同步的 camelCase 格式）。"""

from __future__ import annotations

import calendar
import hashlib
import re
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from urllib.request import url2pathname

# Android NoteColor.DEFAULT_COLORS 的 0xFFRRGGBB 值
DEFAULT_COLORS: list[int] = [
    0xFFFFF9C4,
    0xFFC8E6C9,
    0xFFBBDEFB,
    0xFFFFCDD2,
    0xFFD1C4E9,
    0xFFFFE0B2,
    0xFFB2EBF2,
    0xFFF0F4C3,
]


def now_ms() -> int:
    return int(time.time() * 1000)


def new_id() -> str:
    return str(uuid.uuid4())


FOLDER_MAX_LENGTH = 200


def normalize_folder(value: str) -> str:
    """规范化文件夹路径：反斜杠转正斜杠、逐段去空白、丢弃空段并截断总长。"""
    raw = (value or "").replace("\\", "/")
    parts = (part.strip() for part in raw.split("/"))
    return "/".join(part for part in parts if part)[:FOLDER_MAX_LENGTH]


def folder_parent(folder: str) -> str:
    """父路径；顶层文件夹或未分类返回空串。"""
    if not folder or "/" not in folder:
        return ""
    return folder.rsplit("/", 1)[0]


def folder_ancestors(folder: str) -> list[str]:
    """从顶层到自身的全部路径前缀（不含空串）：'a/b' → ['a', 'a/b']。"""
    result: list[str] = []
    current = ""
    for part in (p for p in folder.split("/") if p):
        current = f"{current}/{part}" if current else part
        result.append(current)
    return result


def folder_in_subtree(candidate: str, root: str) -> bool:
    """candidate 是否等于 root 或位于其子树下；root 为空串时表示未分类，仅精确匹配。"""
    if not root:
        return not candidate
    return candidate == root or candidate.startswith(root + "/")


def folder_sort_key(folder: str) -> tuple:
    """同级目录按名称不区分大小写排序，子级紧跟在父级之后（元组前缀序）。"""
    return tuple(p.casefold() for p in folder.split("/") if p)


@dataclass
class Note:
    id: str = field(default_factory=new_id)
    title: str = ""
    content: str = ""
    color: int = DEFAULT_COLORS[0]
    created_at: int = field(default_factory=now_ms)
    updated_at: int = field(default_factory=now_ms)
    is_pinned: bool = False
    is_deleted: bool = False
    deleted_at: int | None = None
    tags: str = ""
    reminder_time: int | None = None
    is_locked: bool = False
    is_checklist: bool = False
    folder: str = ""
    recurrence: str = ""

    def tag_list(self) -> list[str]:
        if not self.tags.strip():
            return []
        return [t.strip() for t in self.tags.split(",") if t.strip()]

    def set_tags(self, tags: list[str]) -> None:
        seen: list[str] = []
        for tag in tags:
            tag = tag.strip()
            if tag and tag not in seen:
                seen.append(tag)
        self.tags = ",".join(seen)


    def note_content_hash(self) -> str:
        """内容指纹（协议 v5）：同毫秒平局时的确定性仲裁依据（不含 id/updatedAt）。

        注意：recurrence 不参与指纹（保持与旧版本字节一致，避免全量重同步）；
        重复提醒的变化按 updatedAt 正常同步。
        """
        return content_hash_fields(
            title=self.title,
            content=self.content,
            tags=self.tags,
            folder=self.folder,
            color=self.color,
            created_at=self.created_at,
            is_pinned=self.is_pinned,
            is_locked=self.is_locked,
            is_checklist=self.is_checklist,
            reminder_time=self.reminder_time,
            deleted_at=self.deleted_at,
        )


# ---------------------------------------------------------------- 重复提醒

RECURRENCE_CHOICES: tuple[tuple[str, str], ...] = (
    ("", "不重复"),
    ("daily", "每天"),
    ("weekly", "每周"),
    ("monthly", "每月"),
)


def normalize_recurrence(value: str | None) -> str:
    text = (value or "").strip().lower()
    return text if text in ("daily", "weekly", "monthly") else ""


def next_occurrence(base_ms: int, recurrence: str) -> int | None:
    """按重复规则计算下一次提醒时间（本地时区）；规则为空返回 None。

    每月重复时日期超出目标月天数会收拢到当月最后一天（1/31 → 2/28）。
    """
    rule = normalize_recurrence(recurrence)
    if not rule:
        return None
    base = datetime.fromtimestamp(base_ms / 1000)
    if rule == "daily":
        nxt = base + timedelta(days=1)
    elif rule == "weekly":
        nxt = base + timedelta(weeks=1)
    else:
        year = base.year + (1 if base.month == 12 else 0)
        month = 1 if base.month == 12 else base.month + 1
        day = min(base.day, calendar.monthrange(year, month)[1])
        nxt = base.replace(year=year, month=month, day=day)
    return int(nxt.timestamp() * 1000)


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
    """内容指纹的规范化规则（三端一致，跨语言测试向量锁定）。

    除 id/updatedAt 外的全部字段按固定顺序以 \\x1f 连接后取 SHA-256 前 16 位十六进制。
    可空时间戳以 -1 表示；布尔以 1/0 表示。
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


# 独占一行的内联图片标记：`![任意alt](本地路径)`（与 Android 正文格式一致）
IMAGE_LINE_RE = re.compile(r"^!\[[^\]]*]\(([^)]+)\)\s*$")


def image_token_path(line: str) -> str | None:
    """提取一行图片标记中的路径；不是图片行时返回 None。"""
    match = IMAGE_LINE_RE.match(line.strip())
    return match.group(1) if match else None


def image_token(path: str) -> str:
    """生成内联图片标记行（图片必须独占一行才能在预览中渲染）。"""
    return f"![]({Path(path).as_uri()})"


def local_image_path(token_path: str) -> str:
    """图片标记中的 file:// URI 转回本地绝对路径。"""
    return url2pathname(token_path.removeprefix("file://"))


def parse_image_tokens(content: str) -> list[str]:
    """解析正文中所有内联图片路径（按出现顺序）。"""
    return [path for line in content.split("\n") if (path := image_token_path(line))]


# ---------------------------------------------------------------- 便携附件（协议 v6）
# 附件名 = sha256(文件字节) 前 16 位十六进制 + "." + 小写扩展名（≤8 位字母数字）。
# 正文标记：`![](freenote-img:<name>)`——与设备无关，由各端解析到本地附件目录。
ATTACHMENT_SCHEME = "freenote-img:"
_ATTACHMENT_NAME_RE = re.compile(r"^[0-9a-f]{16}\.[a-z0-9]{1,8}$")


def valid_attachment_name(name: str) -> bool:
    return bool(name) and bool(_ATTACHMENT_NAME_RE.match(name))


def attachment_token(name: str) -> str:
    """生成便携附件标记行（图片必须独占一行才能在预览中渲染）。"""
    return f"![]({ATTACHMENT_SCHEME}{name})"


def attachment_name_from_path(token_path: str) -> str | None:
    """从图片标记路径提取便携附件名；非便携形式（file:// 等）返回 None。"""
    if not token_path.startswith(ATTACHMENT_SCHEME):
        return None
    name = token_path[len(ATTACHMENT_SCHEME):]
    return name if valid_attachment_name(name) else None


def content_attachment_names(content: str) -> list[str]:
    """正文中引用的全部便携附件名（按出现顺序，可能重复）。"""
    names: list[str] = []
    for line in content.split("\n"):
        path = image_token_path(line)
        if path is None:
            continue
        name = attachment_name_from_path(path)
        if name:
            names.append(name)
    return names


def strip_image_tokens(content: str, replacement: str = "") -> str:
    """把内联图片行替换为 [replacement]（连续多张图合并为一行），用于列表预览、通知等纯文本场景。"""
    lines: list[str] = []
    pending = False
    for line in content.split("\n"):
        if image_token_path(line) is not None:
            pending = True
            continue
        if pending:
            lines.append(replacement)
            pending = False
        lines.append(line)
    if pending:
        lines.append(replacement)
    return "\n".join(lines).strip()


@dataclass
class ChecklistItem:
    checked: bool = False
    text: str = ""


def parse_checklist(content: str) -> list[ChecklistItem]:
    if not content or not content.strip():
        return [ChecklistItem()]
    items: list[ChecklistItem] = []
    for line in content.split("\n"):
        if line.startswith("- [ ]"):
            items.append(ChecklistItem(False, line[6:]))
        elif line.startswith("- [x]") or line.startswith("- [X]"):
            items.append(ChecklistItem(True, line[6:]))
        elif line.strip():
            items.append(ChecklistItem(False, line))
    return items or [ChecklistItem()]


def serialize_checklist(items: list[ChecklistItem]) -> str:
    lines: list[str] = []
    for item in items:
        # 内联图片标记原样保留（清单模式下不作为勾选项），避免切换模式时丢失图片
        if image_token_path(item.text) is not None:
            lines.append(item.text)
        else:
            lines.append(f"- [{'x' if item.checked else ' '}] {item.text}")
    return "\n".join(lines)


def note_to_dict(note: Note) -> dict:
    """Android BackupManager 备份格式（camelCase，也是 P2P 传输格式）。"""
    return {
        "id": note.id,
        "title": note.title,
        "content": note.content,
        "color": note.color,
        "createdAt": note.created_at,
        "updatedAt": note.updated_at,
        "isPinned": note.is_pinned,
        "tags": note.tags,
        "folder": note.folder,
        "isChecklist": note.is_checklist,
        "isLocked": note.is_locked,
        "reminderTime": note.reminder_time,
        "isDeleted": note.is_deleted,
        "deletedAt": note.deleted_at,
        "recurrence": note.recurrence,
    }


def note_from_dict(data: dict) -> Note:
    def as_int(value, default=None):
        try:
            return int(value)
        except (TypeError, ValueError):
            return default

    def as_bool(value) -> bool:
        return bool(value)

    # color 允许 0（黑色）；仅缺失/非法时回落默认色
    color_value = as_int(data.get("color"))
    reminder = as_int(data.get("reminderTime"), None)
    deleted_at = as_int(data.get("deletedAt"), None)
    created = as_int(data.get("createdAt"), now_ms())
    updated = as_int(data.get("updatedAt"), created)
    return Note(
        id=str(data.get("id") or new_id()),
        title=str(data.get("title") or ""),
        content=str(data.get("content") or ""),
        color=color_value if color_value is not None else DEFAULT_COLORS[0],
        created_at=created,
        updated_at=updated,
        is_pinned=as_bool(data.get("isPinned")),
        is_deleted=as_bool(data.get("isDeleted")),
        deleted_at=deleted_at if deleted_at and deleted_at > 0 else None,
        tags=str(data.get("tags") or ""),
        reminder_time=reminder if reminder and reminder > 0 else None,
        is_locked=as_bool(data.get("isLocked")),
        is_checklist=as_bool(data.get("isChecklist")),
        folder=normalize_folder(str(data.get("folder") or "")),
        recurrence=normalize_recurrence(str(data.get("recurrence") or "")),
    )


def tombstone_note(note_id: str, deleted_at: int) -> Note:
    """硬删除的墓碑：以已删除便签的形式在网络中传播（与 FreeNote 服务端语义一致）。"""
    return Note(
        id=note_id,
        title="",
        content="",
        created_at=deleted_at,
        updated_at=deleted_at,
        is_deleted=True,
        deleted_at=deleted_at,
    )


def format_note_time(millis: int, now: datetime | None = None) -> str:
    """同年显示 MM/dd HH:mm，跨年显示 yyyy/MM/dd HH:mm（与 Android TimeFormat 一致）。"""
    now = now or datetime.now()
    dt = datetime.fromtimestamp(millis / 1000.0)
    if dt.year != now.year:
        return dt.strftime("%Y/%m/%d %H:%M")
    return dt.strftime("%m/%d %H:%M")


def format_full_time(millis: int) -> str:
    return datetime.fromtimestamp(millis / 1000.0).strftime("%Y/%m/%d %H:%M:%S")
