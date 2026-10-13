"""定时自动备份 + WebDAV 上传（Windows）。

- 到达设定间隔（默认 24 小时）后，把全量 JSON 备份（含图片附件）写入备份目录，
  文件名 FreeNote-YYYYmmdd-HHMMSS.json，目录内仅保留最近 10 份；
- 配置了 WebDAV 地址时，把刚生成的备份文件 PUT 上传（HTTP 基本认证）。

自动备份不依赖应用在前台运行：应用启动时与运行期间每 30 分钟检查一次是否到点。
"""

from __future__ import annotations

import datetime as _dt
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote

import requests

from . import backup
from . import config
from .database import NoteStore
from .models import now_ms
from .settings import Settings

_BACKUP_KEEP = 10
_LAST_RUN_KEY = "auto_backup.last_run"


@dataclass
class AutoBackupResult:
    ok: bool
    message: str


def run_auto_backup(
    store: NoteStore, settings: Settings, force: bool = False
) -> AutoBackupResult | None:
    """到点执行自动备份；未启用或未到间隔时返回 None。"""
    if not settings.auto_backup_enabled and not force:
        return None
    if not settings.auto_backup_dir:
        if force:
            return AutoBackupResult(False, "未设置自动备份目录")
        return None
    directory = Path(settings.auto_backup_dir)
    interval_ms = settings.auto_backup_interval_hours * 3600 * 1000
    try:
        last = int(store.meta_get(_LAST_RUN_KEY) or 0)
    except (TypeError, ValueError):
        last = 0
    now = now_ms()
    if not force and now - last < interval_ms:
        return None

    notes = [*store.list_active(), *store.list_trash()]
    try:
        directory.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        return AutoBackupResult(False, f"自动备份目录不可用：{exc}")
    stamp = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    target = directory / f"FreeNote-{stamp}-{now % 1000:03d}.json"
    result = backup.export_notes(notes, target)
    if not result.success:
        return AutoBackupResult(False, f"自动备份失败：{result.message}")

    # 只保留最近 10 份，超出的最旧文件删除（失败不影响本次备份）
    try:
        existing = sorted(directory.glob("FreeNote-*.json"))
        for old in existing[:-_BACKUP_KEEP]:
            old.unlink(missing_ok=True)
    except OSError:
        pass

    store.meta_set(_LAST_RUN_KEY, str(now))
    message = f"自动备份完成：{result.message} → {target.name}"
    if settings.webdav_url:
        error = _upload_webdav(settings, target)
        if error is not None:
            return AutoBackupResult(False, message + f"；WebDAV 上传失败：{error}")
        message += "；已上传 WebDAV"
    return AutoBackupResult(True, message)


def _upload_webdav(settings: Settings, path: Path) -> str | None:
    """把备份文件 PUT 上传到 WebDAV；成功返回 None，失败返回错误描述。"""
    url = settings.webdav_url
    if not url:
        return None
    if "://" not in url:
        return "WebDAV 地址需要以 https:// 开头（明文 HTTP 已阻止）"
    if config.is_plaintext_public_url(url):
        # 备份文件包含便签明文，Basic 认证凭据也会随请求发送；
        # 非内网的明文 HTTP 一律拒绝，避免整份备份在链路上泄露
        return "WebDAV 地址是明文 HTTP 且非内网，已阻止上传以防备份与凭据泄露；请改用 https://"
    target = url.rstrip("/") + "/" + quote(path.name)
    try:
        data = path.read_bytes()
        auth = (settings.webdav_user, settings.webdav_password) if settings.webdav_user else None
        response = requests.put(
            target,
            data=data,
            auth=auth,
            headers={"Content-Type": "application/json"},
            timeout=60,
        )
        if response.status_code >= 300:
            return f"HTTP {response.status_code}"
    except requests.RequestException as exc:
        return str(exc)
    return None
