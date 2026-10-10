"""FreeNote 同步服务端（FastAPI）。

- 账户：注册/登录，PBKDF2-HMAC-SHA256 密码哈希 + Bearer 令牌
- 同步：POST /api/sync 推送本地变更并拉取增量（按 updatedAt 最后写入者获胜）
- 隐私：服务端只存便签原文（锁定的便签对服务端是密文），不解析内容

启动：python run.py  （等价于 uvicorn freenote_server.app:app --host 0.0.0.0 --port 8000）
"""

from __future__ import annotations

import base64
import html
import os
import secrets
import threading
from datetime import datetime
from pathlib import Path
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field

from . import __version__
from .storage import (
    ATTACHMENT_BYTES_PER_MESSAGE,
    ATTACHMENT_MAX_BYTES,
    ATTACHMENTS_PER_MESSAGE,
    Storage,
    note_to_wire,
    now_ms,
    valid_attachment_name,
)

_DB_ENV = "FREENOTE_SERVER_DB"
_ADMIN_ENV = "FREENOTE_ADMIN_TOKEN"
_DEFAULT_DB = Path(__file__).resolve().parent.parent / "data" / "freenote.db"
_MAX_BODY_BYTES = 32 * 1024 * 1024
_MAX_SYNC_NOTES = 5000
_MAX_ATTACHMENT_B64 = (ATTACHMENT_MAX_BYTES * 4 // 3) + 64

storage = Storage(os.environ.get(_DB_ENV) or _DEFAULT_DB)

app = FastAPI(
    title="FreeNote Sync Server",
    version=__version__,
    description="FreeNote 笔记同步服务：多设备增量同步，服务端不解析加密便签内容。",
)


@app.middleware("http")
async def limit_request_body(request: Request, call_next):
    transfer_encoding = request.headers.get("transfer-encoding", "")
    if "chunked" in transfer_encoding.lower():
        return JSONResponse({"detail": "不支持的传输编码"}, status_code=413)
    content_length = request.headers.get("content-length")
    if content_length and content_length.isdigit() and int(content_length) > _MAX_BODY_BYTES:
        return JSONResponse({"detail": "请求体过大"}, status_code=413)
    return await call_next(request)


# ---------------------------------------------------------------------- 模型


class Credentials(BaseModel):
    username: str = Field(min_length=2, max_length=32)
    password: str = Field(min_length=6, max_length=128)


class ChangePasswordRequest(BaseModel):
    oldPassword: str = Field(min_length=1, max_length=128)
    newPassword: str = Field(min_length=6, max_length=128)


class DeleteAccountRequest(BaseModel):
    password: str = Field(min_length=1, max_length=128)


class _RateLimiter:
    """进程内滑动窗口限流：登录/注册/改密等敏感操作防暴力破解（单实例部署够用）。"""

    def __init__(self, max_attempts: int = 10, window_sec: float = 300.0):
        self._max = max_attempts
        self._window_ms = int(window_sec * 1000)
        self._hits: dict[str, list[int]] = {}
        self._lock = threading.Lock()

    def check(self, key: str) -> bool:
        now = now_ms()
        with self._lock:
            entries = [t for t in self._hits.get(key, []) if now - t < self._window_ms]
            if len(entries) >= self._max:
                self._hits[key] = entries
                return False
            entries.append(now)
            self._hits[key] = entries
            if len(self._hits) > 10000:  # 防内存膨胀：丢弃窗口外的键
                self._hits = {
                    k: v for k, v in self._hits.items() if v and now - v[-1] < self._window_ms
                }
            return True


_rate_limiter = _RateLimiter()


def _client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:
        return forwarded.split(",", 1)[0].strip()
    return request.client.host if request.client else "?"


def _enforce_rate_limit(action: str, request: Request, username: str = "") -> None:
    key = f"{action}:{_client_ip(request)}:{username.lower()}"
    if not _rate_limiter.check(key):
        raise HTTPException(status_code=429, detail="尝试过于频繁，请 5 分钟后再试")


def _bearer_token(authorization: str | None) -> str:
    if not authorization or not authorization.lower().startswith("bearer "):
        return ""
    return authorization.split(" ", 1)[1].strip()


class Tombstone(BaseModel):
    id: str
    deletedAt: int | None = None


class AttachmentUpload(BaseModel):
    name: str = Field(max_length=64)
    # base64 文本长度上限：8MB 原始字节 ≈ 10.7MB 文本（含少量余量）
    data: str = Field(min_length=1, max_length=_MAX_ATTACHMENT_B64)


class SyncRequest(BaseModel):
    sinceSeq: int = 0
    notes: list[dict[str, Any]] = Field(default_factory=list, max_length=_MAX_SYNC_NOTES)
    tombstones: list[Tombstone] = Field(default_factory=list, max_length=_MAX_SYNC_NOTES)
    attachments: list[AttachmentUpload] = Field(
        default_factory=list, max_length=ATTACHMENTS_PER_MESSAGE
    )
    wantAttachments: list[str] = Field(
        default_factory=list, max_length=ATTACHMENTS_PER_MESSAGE
    )


class AuthResponse(BaseModel):
    token: str
    username: str


# ---------------------------------------------------------------------- 鉴权


def current_user(authorization: str | None = Header(default=None)) -> int:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="缺少访问令牌")
    token = authorization.split(" ", 1)[1].strip()
    user_id = storage.user_by_token(token)
    if user_id is None:
        raise HTTPException(status_code=401, detail="令牌无效，请重新登录")
    return user_id


# ---------------------------------------------------------------------- 路由


@app.get("/")
def index() -> dict:
    return {
        "name": "FreeNote Sync Server",
        "version": __version__,
        "docs": "/docs",
    }


@app.get("/api/health")
def health() -> dict:
    return {"status": "ok", "version": __version__}


@app.post("/api/register", response_model=AuthResponse, status_code=201)
def register(credentials: Credentials, request: Request) -> AuthResponse:
    _enforce_rate_limit("register", request, credentials.username.strip())
    username = credentials.username.strip()
    if not username:
        raise HTTPException(status_code=422, detail="用户名不能为空")
    try:
        user_id = storage.create_user(username, credentials.password)
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    token = storage.create_token(user_id)
    return AuthResponse(token=token, username=username)


@app.post("/api/login", response_model=AuthResponse)
def login(credentials: Credentials, request: Request) -> AuthResponse:
    username = credentials.username.strip()
    _enforce_rate_limit("login", request, username)
    user_id = storage.verify_user(username, credentials.password)
    if user_id is None:
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    token = storage.create_token(user_id)
    return AuthResponse(token=token, username=username)


@app.post("/api/logout")
def logout(user_id: int = Depends(current_user), authorization: str = Header(default="")) -> dict:
    token = authorization.split(" ", 1)[1].strip()
    storage.revoke_token(token)
    return {"ok": True}


@app.post("/api/change_password")
def change_password(
    body: ChangePasswordRequest,
    request: Request,
    user_id: int = Depends(current_user),
    authorization: str = Header(default=""),
) -> dict:
    """修改密码：校验旧密码；成功后撤销其他设备的登录令牌（保留当前会话）。"""
    _enforce_rate_limit("change_password", request, "")
    if not storage.change_password(user_id, body.oldPassword, body.newPassword):
        raise HTTPException(status_code=401, detail="旧密码不正确")
    token = _bearer_token(authorization)
    revoked = storage.revoke_other_tokens(user_id, token) if token else 0
    return {"ok": True, "revokedTokens": revoked}


@app.post("/api/delete_account")
def delete_account(
    body: DeleteAccountRequest,
    request: Request,
    user_id: int = Depends(current_user),
) -> dict:
    """注销账号：校验密码后删除该用户在服务端的全部数据（本机数据不受影响）。"""
    _enforce_rate_limit("delete_account", request, "")
    if not storage.verify_password_for_user(user_id, body.password):
        raise HTTPException(status_code=401, detail="密码不正确")
    storage.delete_user(user_id)
    return {"ok": True}


@app.get("/admin", response_class=HTMLResponse)
def admin_panel(request: Request, token: str = "") -> HTMLResponse:
    """只读管理面板：设置环境变量 FREENOTE_ADMIN_TOKEN 后经 ?token= 或 Bearer 访问。"""
    admin_token = os.environ.get(_ADMIN_ENV, "").strip()
    if not admin_token:
        raise HTTPException(
            status_code=404,
            detail="未启用管理面板：请设置环境变量 FREENOTE_ADMIN_TOKEN 后重启服务",
        )
    supplied = token or _bearer_token(request.headers.get("authorization"))
    if not supplied or not secrets.compare_digest(
        supplied.encode("utf-8"), admin_token.encode("utf-8")
    ):
        raise HTTPException(status_code=401, detail="管理令牌无效")

    rows = storage.admin_overview()
    total_bytes = sum(int(row["attachment_bytes"] or 0) for row in rows)
    lines = [
        "<!doctype html><html lang='zh'><head><meta charset='utf-8'>",
        "<title>FreeNote 服务端管理</title>",
        "<style>body{font-family:system-ui,'Microsoft YaHei',sans-serif;margin:24px;}",
        "table{border-collapse:collapse;} th,td{border:1px solid #d0c8d8;padding:6px 10px;",
        "font-size:14px;} th{background:#f3eff8;} h1{font-size:20px;}</style></head><body>",
        f"<h1>FreeNote 服务端管理（v{html.escape(__version__)}）</h1>",
        f"<p>用户 {len(rows)} 个 · 附件合计 {total_bytes / (1024 * 1024):.1f} MB</p>",
        "<table><tr><th>ID</th><th>用户名</th><th>注册时间</th><th>令牌</th>",
        "<th>活跃便签</th><th>含回收站</th><th>附件数</th><th>附件大小</th></tr>",
    ]
    for row in rows:
        created = datetime.fromtimestamp(row["created_at"] / 1000).strftime("%Y-%m-%d %H:%M")
        size_mb = int(row["attachment_bytes"] or 0) / (1024 * 1024)
        lines.append(
            "<tr>"
            f"<td>{int(row['id'])}</td>"
            f"<td>{html.escape(str(row['username']))}</td>"
            f"<td>{created}</td>"
            f"<td>{int(row['tokens'])}</td>"
            f"<td>{int(row['active_notes'])}</td>"
            f"<td>{int(row['total_notes'])}</td>"
            f"<td>{int(row['attachment_count'])}</td>"
            f"<td>{size_mb:.1f} MB</td>"
            "</tr>"
        )
    lines.append("</table></body></html>")
    return HTMLResponse("".join(lines))


def _decode_attachment(text: str) -> bytes | None:
    try:
        data = base64.b64decode(text, validate=True)
    except Exception:  # noqa: BLE001 - 非法 base64 一律按无效附件处理
        return None
    if not data or len(data) > ATTACHMENT_MAX_BYTES:
        return None
    return data


@app.post("/api/sync")
def sync(request: SyncRequest, user_id: int = Depends(current_user)) -> dict:
    """推送本地变更并返回服务端自 sinceSeq 之后的全部变更（协议 v6 含附件）。"""
    tombstones = [item.model_dump() for item in request.tombstones]
    applied, conflicts = storage.apply_push(user_id, request.notes, tombstones)

    # 附件上传：内容寻址去重；配额不足/校验失败进入 rejected（客户端不会记为已上传）
    stored: list[str] = []
    rejected: list[str] = []
    for item in request.attachments:
        data = _decode_attachment(item.data)
        if data is None or not valid_attachment_name(item.name):
            rejected.append(item.name)
            continue
        try:
            if storage.store_attachment(user_id, item.name, data):
                stored.append(item.name)
            else:
                rejected.append(item.name)
        except ValueError:
            rejected.append(item.name)

    # 附件下载：按请求名单返回（数量/字节双上限，超出部分下轮再取）
    wanted: list[str] = []
    for name in request.wantAttachments:
        if valid_attachment_name(name) and name not in wanted:
            wanted.append(name)
    blobs = storage.attachments_by_names(user_id, wanted)
    downloads: list[dict] = []
    total = 0
    for name in wanted:
        data = blobs.get(name)
        if data is None:
            continue
        if (
            len(downloads) >= ATTACHMENTS_PER_MESSAGE
            or total + len(data) > ATTACHMENT_BYTES_PER_MESSAGE
        ):
            break
        downloads.append({"name": name, "data": base64.b64encode(data).decode("ascii")})
        total += len(data)

    seq = storage.current_seq(user_id)
    rows = storage.changes_since(user_id, request.sinceSeq)
    return {
        "seq": seq,
        "applied": applied,
        "conflicts": conflicts,
        "notes": [note_to_wire(row) for row in rows],
        "attachments": downloads,
        "attachmentsStored": stored,
        "attachmentsRejected": rejected,
        "capabilities": ["attachments"],
    }
