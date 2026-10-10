"""附件（图片）工具：内容寻址命名、legacy 迁移、收发校验、清理。

三端一致的规则（协议 v6）：
- 附件文件名 = sha256(文件字节) 前 16 位十六进制 + "." + 小写扩展名（≤8 位字母数字）；
- 正文以 `![](freenote-img:<name>)` 引用（便携标记，与设备路径无关）；
- legacy 的 `![](file://...)` 标记在启动/保存时迁移为便携标记（复制进附件目录）；
- 跨设备同步按 name 传输字节，收到时用 name 里的哈希前缀校验完整性。
"""

from __future__ import annotations

import base64
import hashlib
import re
import shutil
from pathlib import Path

from . import config
from .models import (
    attachment_name_from_path,
    attachment_token,
    content_attachment_names,
    image_token_path,
    local_image_path,
    valid_attachment_name,
)

_HASH_CHUNK = 1024 * 1024


def _safe_ext(suffix: str) -> str:
    """扩展名规范化：去掉点与非字母数字字符，小写，最多 8 位；空则用 img。"""
    cleaned = re.sub(r"[^a-z0-9]", "", (suffix or "").lower())
    return cleaned[:8] or "img"


def name_for_bytes(data: bytes, suffix: str) -> str:
    return f"{hashlib.sha256(data).hexdigest()[:16]}.{_safe_ext(suffix)}"


def name_for_file(path: str | Path) -> str:
    """按文件内容计算附件名（流式读取，避免大图全量载入内存）。"""
    source = Path(path)
    digest = hashlib.sha256()
    with open(source, "rb") as handle:
        while chunk := handle.read(_HASH_CHUNK):
            digest.update(chunk)
    return f"{digest.hexdigest()[:16]}.{_safe_ext(source.suffix)}"


def local_path(name: str) -> Path:
    return config.attachments_dir() / name


def local_names() -> set[str]:
    """附件目录中全部合法命名的文件。"""
    try:
        entries = config.attachments_dir().iterdir()
    except OSError:
        return set()
    return {entry.name for entry in entries if entry.is_file() and valid_attachment_name(entry.name)}


def referenced_names(store) -> tuple[set[str], set[str]]:
    """全部便签（含回收站）引用的便携附件名；返回 (引用集合, 本机已有集合)。

    锁定便签的正文是密文，无法解析引用（解锁后由编辑保存路径迁移/收集）；
    墓碑正文为空，无影响。
    """
    present = local_names()
    referenced: set[str] = set()
    try:
        notes = (*store.list_active(), *store.list_trash())
    except Exception:  # noqa: BLE001 - 存储层异常不应阻断同步
        return set(), present
    for note in notes:
        if note.is_locked:
            continue
        referenced.update(content_attachment_names(note.content))
    return referenced, referenced & present


def missing_names(store) -> list[str]:
    referenced, present = referenced_names(store)
    return sorted(referenced - present)


# ---------------------------------------------------------------------- 迁移


def migrate_content(content: str) -> tuple[str, int]:
    """把正文中可解析的 legacy `file://` 图片行改写为便携标记。

    原文件复制（保留原文件，避免用户其他笔记/外部引用受影响）进附件目录并按内容命名；
    文件不可读或复制失败时保留原样。返回 (新正文, 迁移张数)。
    """
    lines: list[str] = []
    changed = 0
    for line in content.split("\n"):
        path = image_token_path(line)
        if path is None or attachment_name_from_path(path) is not None:
            lines.append(line)
            continue
        if not path.startswith("file://"):
            lines.append(line)
            continue
        try:
            source = Path(local_image_path(path))
            if not source.is_file():
                lines.append(line)
                continue
            name = name_for_file(source)
            target = local_path(name)
            if not target.exists():
                shutil.copy2(source, target)
            lines.append(attachment_token(name))
            changed += 1
        except (OSError, ValueError):
            lines.append(line)
    if not changed:
        return content, 0
    return "\n".join(lines), changed


def migrate_store(store) -> tuple[int, int, int]:
    """扫描并迁移未锁定便签中的 legacy 图片引用。

    返回 (匹配扫描数, 迁移便签数, 迁移图片数)；迁移会更新 updatedAt 并进入同步队列。
    """
    scanned = migrated_notes = migrated_images = 0
    for note in (*store.list_active(), *store.list_trash()):
        if note.is_locked or "](file://" not in note.content:
            continue
        scanned += 1
        new_content, changed = migrate_content(note.content)
        if not changed:
            continue
        note.content = new_content
        store.save(note)
        migrated_notes += 1
        migrated_images += changed
    return scanned, migrated_notes, migrated_images


# ---------------------------------------------------------------------- 收发


def store_blob(name: str, data: bytes) -> bool:
    """写入收到的附件；名称与内容哈希不符、超限或写盘失败返回 False。"""
    if not valid_attachment_name(name) or not data or len(data) > config.MAX_ATTACHMENT_BYTES:
        return False
    if not name.startswith(hashlib.sha256(data).hexdigest()[:16]):
        return False
    target = local_path(name)
    if target.exists():
        return True
    try:
        temp = target.with_name(target.name + ".part")
        temp.write_bytes(data)
        temp.replace(target)
    except OSError:
        return False
    return True


def blob_data(name: str) -> bytes | None:
    """读取本地附件字节；不存在或超限返回 None。"""
    if not valid_attachment_name(name):
        return None
    path = local_path(name)
    try:
        if not path.is_file() or path.stat().st_size > config.MAX_ATTACHMENT_BYTES:
            return None
        return path.read_bytes()
    except OSError:
        return None


def encode_wire(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def decode_wire(text) -> bytes | None:
    """解码 base64 附件载荷；非法编码或超限返回 None。"""
    if not isinstance(text, str) or not text:
        return None
    if len(text) > (config.MAX_ATTACHMENT_BYTES * 4 // 3) + 64:
        return None
    try:
        data = base64.b64decode(text, validate=True)
    except (ValueError, TypeError):
        return None
    if len(data) > config.MAX_ATTACHMENT_BYTES:
        return None
    return data


def valid_name_list(raw) -> list[str]:
    """解析对端传来的附件名列表：去重、校验命名、截断上限。"""
    if not isinstance(raw, list):
        return []
    names: list[str] = []
    seen: set[str] = set()
    for item in raw:
        name = str(item or "")
        if name in seen or not valid_attachment_name(name):
            continue
        seen.add(name)
        names.append(name)
        if len(names) >= config.ATTACHMENT_INVENTORY_LIMIT:
            break
    return names


def take_batch(names: list[str], max_count: int = config.MAX_ATTACHMENTS_PER_MESSAGE) -> list[str]:
    """按数量与字节上限从候选名中取出一批本地可读的附件名。"""
    batch: list[str] = []
    total = 0
    for name in names:
        if len(batch) >= max_count:
            break
        path = local_path(name)
        try:
            size = path.stat().st_size
        except OSError:
            continue
        if size <= 0 or size > config.MAX_ATTACHMENT_BYTES:
            continue
        if total + size > config.MAX_ATTACHMENT_BYTES_PER_MESSAGE:
            break
        batch.append(name)
        total += size
    return batch


# ---------------------------------------------------------------------- 清理与渲染


def delete_unused(names: set[str], store) -> int:
    """删除不再被任何便签引用的本地附件；返回删除数量。"""
    if not names:
        return 0
    referenced, _ = referenced_names(store)
    removed = 0
    for name in names:
        if name in referenced:
            continue
        try:
            local_path(name).unlink(missing_ok=True)
            removed += 1
        except OSError:
            continue
    return removed


def resolve_for_render(content: str) -> str:
    """把便携标记解析为本地 file:// 路径供 Markdown 预览渲染；缺失的标记原样保留。"""
    lines: list[str] = []
    for line in content.split("\n"):
        path = image_token_path(line)
        name = attachment_name_from_path(path) if path else None
        if name:
            candidate = local_path(name)
            if candidate.is_file():
                lines.append(f"![]({candidate.resolve().as_uri()})")
                continue
        lines.append(line)
    return "\n".join(lines)
