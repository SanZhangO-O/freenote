"""JSON 备份导出/导入，格式与 Android 版 BackupManager 一致（协议 v6 含图片附件）。

备份结构：{app, version, exportedAt, notes: [...], attachments: [{name, data(base64)}]}。
附件按内容寻址（name = sha256 前 16 位 + 扩展名），导入端校验哈希后写入附件目录；
旧版本客户端会忽略 attachments 字段（向后兼容）。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from . import attachments
from .database import NoteStore
from .models import Note, content_attachment_names, note_from_dict, note_to_dict, now_ms

BACKUP_VERSION = 6
_MAX_EXPORT_IMAGE_BYTES = 64 * 1024 * 1024  # 单张超过此大小则跳过（避免备份文件失控）
_TEXT_SUFFIXES = {".md", ".markdown", ".txt"}
_ENEX_SUFFIX = ".enex"


@dataclass
class BackupResult:
    success: bool
    message: str
    count: int = 0


def export_notes(notes: list[Note], path: str | Path) -> BackupResult:
    try:
        image_names: set[str] = set()
        for note in notes:
            if not note.is_locked:
                image_names.update(content_attachment_names(note.content))

        blobs: list[dict] = []
        skipped = 0
        truncated = False
        total_bytes = 0
        for name in sorted(image_names):
            data = attachments.blob_data(name)
            if data is None:
                continue
            if len(data) > _MAX_EXPORT_IMAGE_BYTES:
                skipped += 1
                continue
            if total_bytes + len(data) > _MAX_EXPORT_IMAGE_BYTES * 20:
                truncated = True  # 单个备份文件总上限约 1.2GB 原始
                break
            blobs.append({"name": name, "data": attachments.encode_wire(data)})
            total_bytes += len(data)

        payload = {
            "app": "FreeNote",
            "version": BACKUP_VERSION,
            "exportedAt": now_ms(),
            "notes": [note_to_dict(n) for n in notes],
            "attachments": blobs,
        }
        Path(path).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError as exc:  # 磁盘满/无权限/路径不存在
        return BackupResult(False, f"无法写入文件：{exc.strerror or exc}")
    except Exception:  # noqa: BLE001 - 打包为结果对象供 UI 显示
        return BackupResult(False, "导出失败，请确认目标位置可写且磁盘空间充足")
    note = f"导出成功：{len(notes)} 条，含图片 {len(blobs)} 张"
    if skipped:
        note += f"（跳过 {skipped} 张超大图片）"
    if truncated:
        note += "（图片总量超出单次导出上限，其余图片未包含）"
    return BackupResult(True, note, len(notes))


def import_text_file(store: NoteStore, path: str | Path) -> bool:
    """把 .md/.txt 文件导入为一条便签。

    首个 `# ` 一级标题作为标题（并从正文移除），否则用文件名作标题；
    创建/修改时间采用文件时间，便于导入后仍按原时间排序。
    """
    source = Path(path)
    text: str | None = None
    for encoding in ("utf-8-sig", "utf-8", "gbk"):
        try:
            text = source.read_text(encoding=encoding)
            break
        except UnicodeDecodeError:
            continue
        except OSError:
            return False
    if text is None:
        return False
    title = source.stem.strip() or "导入便签"
    content = text
    lines = text.split("\n")
    for index, line in enumerate(lines[:10]):
        if line.startswith("# "):
            candidate = line[2:].strip()
            if candidate:
                title = candidate[:200]
            content = "\n".join(lines[:index] + lines[index + 1:])
            break
    try:
        stamp = int(source.stat().st_mtime * 1000)
    except OSError:
        stamp = now_ms()
    note = Note(
        title=title,
        content=content.strip(),
        created_at=stamp,
        updated_at=stamp,
    )
    store.apply_remote(note)
    store.enqueue_sync(note.id)
    return True


def _import_plain_text_files(store: NoteStore, paths: list[str | Path]) -> BackupResult:
    imported = skipped = 0
    for item in paths:
        if import_text_file(store, item):
            imported += 1
        else:
            skipped += 1
    if imported == 0 and skipped > 0:
        return BackupResult(False, f"导入失败：{skipped} 个文件无法读取")
    message = f"导入完成：新增 {imported} 条文本便签"
    if skipped:
        message += f"，{skipped} 个文件跳过（编码无法识别）"
    return BackupResult(True, message, imported)


def _enml_to_text(html_text: str) -> str:
    """把 Evernote ENML（HTML 子集）转成纯文本/Markdown，保留标题与列表结构。"""
    import html as html_module
    import re

    text = html_text or ""
    text = re.sub(r"(?is)<en-media[^>]*>", "[Evernote 附件未导入]", text)
    text = re.sub(r"(?is)<br\s*/?>", "\n", text)
    text = re.sub(r"(?is)<h1[^>]*>", "\n# ", text)
    text = re.sub(r"(?is)<h2[^>]*>", "\n## ", text)
    text = re.sub(r"(?is)<h3[^>]*>", "\n### ", text)
    text = re.sub(r"(?is)<li[^>]*>", "\n- ", text)
    text = re.sub(r"(?is)</(div|p|h1|h2|h3|li|ul|ol|en-note)>", "\n", text)
    text = re.sub(r"(?is)<[^>]+>", "", text)
    text = html_module.unescape(text)
    lines = [line.rstrip() for line in text.split("\n")]
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    return "\n".join(lines)


def import_enex_file(store: NoteStore, path: str | Path) -> int:
    """导入 Evernote `.enex` 导出文件；返回新增便签数（-1 表示解析失败）。

    附件（en-media）不入库，正文中以占位文字标记；创建时间按 ENEX 的 created 字段还原。
    """
    import datetime as _dt
    import xml.etree.ElementTree as element_tree

    try:
        tree = element_tree.parse(Path(path))
    except (OSError, element_tree.ParseError):
        return -1
    count = 0
    for note_element in tree.getroot().iter("note"):
        title_element = note_element.find("title")
        content_element = note_element.find("content")
        title = (title_element.text or "").strip() if title_element is not None else ""
        content = _enml_to_text(content_element.text or "") if content_element is not None else ""
        if not title and not content:
            continue
        stamp = 0
        created_element = note_element.find("created")
        if created_element is not None and (created_element.text or "").strip():
            value = created_element.text.strip()
            for pattern in ("%Y%m%dT%H%M%SZ", "%Y-%m-%dT%H:%M:%SZ"):
                try:
                    stamp = int(_dt.datetime.strptime(value, pattern).timestamp() * 1000)
                    break
                except ValueError:
                    continue
        if not stamp:
            stamp = now_ms()
        note = Note(
            title=(title or "Evernote 便签")[:200],
            content=content,
            created_at=stamp,
            updated_at=stamp,
        )
        store.apply_remote(note)
        store.enqueue_sync(note.id)
        count += 1
    return count


def import_enex_files(store: NoteStore, paths: list[str | Path]) -> BackupResult:
    imported = skipped = 0
    for item in paths:
        if Path(item).suffix.lower() != _ENEX_SUFFIX:
            continue
        result = import_enex_file(store, item)
        if result < 0:
            skipped += 1
        else:
            imported += result
    if imported == 0 and skipped > 0:
        return BackupResult(False, f"Evernote 导入失败：{skipped} 个文件无法解析")
    message = f"Evernote 导入完成：新增 {imported} 条便签"
    if skipped:
        message += f"，{skipped} 个文件跳过"
    return BackupResult(True, message, imported)


def import_text_files(store: NoteStore, paths: list[str | Path]) -> BackupResult:
    """导入 .md/.txt/.enex：文本文件每个一条，ENEX 按内部便签展开。"""
    text_paths = [p for p in paths if Path(p).suffix.lower() != _ENEX_SUFFIX]
    enex_paths = [p for p in paths if Path(p).suffix.lower() == _ENEX_SUFFIX]
    messages: list[str] = []
    total = 0
    if text_paths:
        result = _import_plain_text_files(store, text_paths)
        messages.append(result.message)
        total += result.count
    if enex_paths:
        result = import_enex_files(store, enex_paths)
        messages.append(result.message)
        total += result.count
    if not messages:
        return BackupResult(True, "没有可导入的文件", 0)
    return BackupResult(True, "；".join(messages), total)


def collect_import_files(paths: list[str | Path]) -> tuple[list[Path], list[Path]]:
    """整理拖入的路径：返回 (文本/ENEX 文件, JSON 备份)；目录展开一层。"""
    texts: list[Path] = []
    backups: list[Path] = []
    for item in paths:
        path = Path(item)
        try:
            if path.is_dir():
                for child in sorted(path.iterdir()):
                    if child.is_file() and (
                        child.suffix.lower() in _TEXT_SUFFIXES
                        or child.suffix.lower() == _ENEX_SUFFIX
                    ):
                        texts.append(child)
            elif path.is_file():
                suffix = path.suffix.lower()
                if suffix in _TEXT_SUFFIXES or suffix == _ENEX_SUFFIX:
                    texts.append(path)
                elif suffix == ".json":
                    backups.append(path)
        except OSError:
            continue
    return texts, backups


def import_notes(store: NoteStore, path: str | Path) -> BackupResult:
    try:
        raw = Path(path).read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return BackupResult(False, "文件编码无法识别，请选择 FreeNote 导出的 JSON 备份文件")
    except OSError as exc:
        return BackupResult(False, f"无法读取文件：{exc.strerror or exc}")
    try:
        root = json.loads(raw)
    except ValueError:
        return BackupResult(False, "文件不是有效的 JSON，可能不是 FreeNote 备份文件")

    if not isinstance(root, dict) or not isinstance(root.get("notes"), list):
        return BackupResult(False, "文件格式无效：缺少 notes 数组")

    imported_images = 0
    attachments_raw = root.get("attachments")
    if isinstance(attachments_raw, list):
        for item in attachments_raw:
            if not isinstance(item, dict):
                continue
            name = str(item.get("name") or "")
            data = attachments.decode_wire(item.get("data"))
            if data is not None and attachments.store_blob(name, data):
                imported_images += 1

    count = updated = skipped = locked = trashed = 0
    for item in root["notes"]:
        if not isinstance(item, dict):
            continue
        note = note_from_dict(item)
        existing = store.get(note.id)
        if existing is None:
            store.apply_remote(note)
            store.enqueue_sync(note.id, deleted=note.is_deleted)
            count += 1
        elif store.apply_remote_lww(note):
            # 备份里的版本较新：覆盖本机旧副本，并纳入服务器同步队列
            store.enqueue_sync(note.id, deleted=note.is_deleted)
            updated += 1
        else:
            # 本机版本较新（或内容相同）：保留本机数据，不覆盖
            skipped += 1
            continue
        if note.is_locked:
            locked += 1
        if note.is_deleted:
            trashed += 1

    parts = [f"新增 {count} 条"]
    if updated:
        parts.append(f"更新 {updated} 条")
    if skipped:
        parts.append(f"跳过 {skipped} 条（本机版本较新）")
    message = "导入完成：" + "，".join(parts)
    if imported_images:
        message += f"；含图片 {imported_images} 张"
    if trashed:
        message += f"；其中 {trashed} 条为回收站便签"
    if locked:
        message += f"；含 {locked} 条加密便签，需使用原 PIN 解锁"
    return BackupResult(True, message, count + updated)
