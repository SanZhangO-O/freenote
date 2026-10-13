"""服务器同步客户端：与 FreeNote 服务端（server/）的 /api/* 通信。

协议（最后写入者获胜，LWW）：
- 推送：本地待同步队列中的便签（含 isDeleted 墓碑）逐条发送；
- 拉取：服务端返回 sinceSeq 之后的所有变更，本地按 updatedAt 比较后应用；
- 服务端只保存密文，锁定便签对服务器不可见。

Android 版使用同一套服务端协议，因此两端（Android / Windows）可以通过同一账号在服务端交汇。
"""

from __future__ import annotations

import json
import threading
from dataclasses import dataclass

import requests
import urllib3

from . import attachments, config
from .database import NoteStore
from .models import note_from_dict, note_to_dict, now_ms
from .settings import Settings

_TIMEOUT = 20
_USER_AGENT = "FreeNote-Windows/1.0"


def _needs_https_hint(url: str) -> bool:
    """明文 HTTP 且目标不是本机/内网地址时返回 True（用于提示改用 HTTPS）。"""
    return config.is_plaintext_public_url(url)


class SyncError(Exception):
    pass


def _short_network_reason(exc: BaseException) -> str:
    """把 requests 的长异常链压缩成用户能看懂的一句话。"""
    name = type(exc).__name__
    if isinstance(exc, (requests.exceptions.ConnectTimeout, requests.exceptions.ReadTimeout)):
        return "连接超时"
    if isinstance(exc, requests.exceptions.SSLError):
        return "HTTPS 证书校验失败"
    if isinstance(exc, requests.exceptions.ConnectionError):
        return "无法建立连接"
    return {
        "Timeout": "连接超时",
        "ConnectionError": "无法建立连接",
    }.get(name, name or "网络错误")


@dataclass
class SyncResult:
    ok: bool
    message: str
    pushed: int = 0
    pulled: int = 0
    images_uploaded: int = 0
    images_downloaded: int = 0
    images_pending: int = 0


@dataclass
class AuthResult:
    ok: bool
    message: str


def _error_message(response: requests.Response) -> str:
    try:
        payload = response.json()
        detail = payload.get("detail") or payload.get("error")
        if detail:
            if isinstance(detail, list):
                parts: list[str] = []
                for item in detail:
                    if not isinstance(item, dict):
                        parts.append(str(item))
                        continue
                    loc = item.get("loc")
                    field = str(loc[-1]) if isinstance(loc, (list, tuple)) and loc else ""
                    msg = str(item.get("msg") or item)
                    parts.append(f"{field}: {msg}" if field and field != "body" else msg)
                return "; ".join(parts)
            return str(detail)
    except Exception:  # noqa: BLE001
        pass
    return f"HTTP {response.status_code}"


def normalize_server_url(text: str) -> str:
    """补全协议前缀并去掉尾部斜杠，例如：
    192.168.1.8:8000 → http://192.168.1.8:8000（本机/内网保持明文 HTTP）；
    example.com:8000 → https://example.com:8000（公网默认 HTTPS，避免凭据走明文）。
    """
    value = (text or "").strip().rstrip("/")
    if not value:
        return ""
    if "://" not in value:
        scheme = "http" if config.is_local_host(config.host_of(value)) else "https"
        value = f"{scheme}://" + value
    return value


class SyncClient:
    def __init__(self, store: NoteStore, settings: Settings):
        self.store = store
        self.settings = settings
        self._lock = threading.Lock()
        self._session = requests.Session()
        self._session.headers["User-Agent"] = _USER_AGENT

    # ------------------------------------------------------------------ 基础请求

    def _request(
        self,
        method: str,
        path: str,
        body: dict | None = None,
        auth: bool = True,
        server_url: str | None = None,
        timeout: int = _TIMEOUT,
    ) -> dict:
        base = normalize_server_url(server_url or self.settings.server_url)
        if not base:
            raise SyncError("尚未配置服务器地址")
        headers = {}
        used_token = ""
        if auth:
            if not self.settings.server_token:
                raise SyncError("尚未登录，请先在设置中登录或注册")
            used_token = self.settings.server_token
            headers["Authorization"] = f"Bearer {used_token}"
        try:
            # 传输加密：HTTPS 证书默认严格校验；自签名证书可在设置中跳过校验
            verify = not self.settings.server_tls_no_verify
            if not verify:
                urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
            with self._lock:
                response = self._session.request(
                    method, f"{base}{path}", json=body, headers=headers, timeout=timeout, verify=verify
                )
        except requests.exceptions.Timeout as exc:
            raise SyncError("连接服务器超时") from exc
        except requests.exceptions.SSLError as exc:
            if self.settings.server_tls_no_verify:
                raise SyncError(f"HTTPS 连接失败：{exc}") from exc
            raise SyncError(
                "HTTPS 证书校验失败；自签名证书可在设置中勾选「跳过 HTTPS 证书校验」"
            ) from exc
        except requests.exceptions.RequestException as exc:
            raise SyncError(f"无法连接服务器（{_short_network_reason(exc)}），请检查地址与网络") from exc

        if response.status_code >= 400:
            message = _error_message(response)
            if auth and response.status_code == 401:
                # 令牌失效：仅当本地令牌未被中途替换（如在途请求期间新登录）时才清除
                if self.settings.server_token == used_token:
                    self.settings.server_token = ""
                    message = "登录已过期，请在「设置 → 服务器同步」中重新登录"
            raise SyncError(message)
        try:
            return response.json()
        except ValueError as exc:
            raise SyncError("服务器返回了无效数据") from exc

    def _save_token(self, token: str, username: str) -> None:
        self.settings.set("server_token", token, save=False)
        self.settings.set("server_username", username.strip())

    # ------------------------------------------------------------------ 账户

    def register(self, server_url: str, username: str, password: str) -> AuthResult:
        try:
            payload = self._request(
                "POST", "/api/register",
                {"username": username, "password": password},
                auth=False, server_url=server_url,
            )
        except SyncError as exc:
            return AuthResult(False, str(exc))
        self.settings.server_url = normalize_server_url(server_url)
        self._save_token(str(payload.get("token") or ""), username)
        return AuthResult(True, f"注册成功，已登录为 {username}")

    def login(self, server_url: str, username: str, password: str) -> AuthResult:
        try:
            payload = self._request(
                "POST", "/api/login",
                {"username": username, "password": password},
                auth=False, server_url=server_url,
            )
        except SyncError as exc:
            return AuthResult(False, str(exc))
        self.settings.server_url = normalize_server_url(server_url)
        self._save_token(str(payload.get("token") or ""), username)
        return AuthResult(True, f"登录成功，欢迎 {username}")

    def logout(self) -> None:
        try:
            self._request("POST", "/api/logout", {})
        except SyncError:
            pass
        self.settings.server_token = ""

    def change_password(self, old_password: str, new_password: str) -> AuthResult:
        """修改服务器账号密码；成功后其他设备的登录令牌失效（本机保持登录）。"""
        try:
            payload = self._request(
                "POST", "/api/change_password",
                {"oldPassword": old_password, "newPassword": new_password},
            )
        except SyncError as exc:
            return AuthResult(False, str(exc))
        revoked = int(payload.get("revokedTokens") or 0)
        message = "密码已修改"
        if revoked:
            message += f"，已退出其他 {revoked} 个设备，请在新设备上重新登录"
        return AuthResult(True, message)

    def delete_account(self, password: str) -> AuthResult:
        """注销服务器账号并删除服务端全部数据；本机便签数据保留。"""
        try:
            self._request("POST", "/api/delete_account", {"password": password})
        except SyncError as exc:
            return AuthResult(False, str(exc))
        self.settings.server_token = ""
        return AuthResult(True, "账号已注销，服务端数据已删除（本机便签保留，可继续本地/P2P 使用）")

    # ------------------------------------------------------------------ 同步

    def _last_seq_key(self) -> str:
        server = normalize_server_url(self.settings.server_url)
        username = (self.settings.server_username or "").strip()
        return f"last_seq:{server}:{username}"

    def _load_last_seq(self) -> int:
        key = self._last_seq_key()
        value = self.store.meta_get(key)
        if value is None:
            # 旧版本把 seq 存在全局 last_seq 键：首次读取迁移到按服务器+账户区分的键
            value = self.store.meta_get("last_seq", "0") or "0"
            self.store.meta_set(key, value)
        return int(value or 0)

    _ATTACHMENT_UPLOADED_KEY = "attachments.uploaded"

    def _uploaded_key(self) -> str:
        """附件上传记录按「服务器 + 账号」隔离，换账号后重新上传，不误判为已传。"""
        server = normalize_server_url(self.settings.server_url)
        username = (self.settings.server_username or "").strip()
        return f"{self._ATTACHMENT_UPLOADED_KEY}:{server}:{username}"

    def _uploaded_names(self) -> set[str]:
        raw = self.store.meta_get(self._uploaded_key())
        if not raw:
            return set()
        try:
            values = json.loads(raw)
        except ValueError:
            return set()
        if not isinstance(values, list):
            return set()
        return {str(value) for value in values if isinstance(value, str)}

    def _save_uploaded_names(self, names: set[str]) -> None:
        self.store.meta_set(self._uploaded_key(), json.dumps(sorted(names)))

    def sync(self) -> SyncResult:
        since_seq = self._load_last_seq()

        outbox = self.store.outbox()
        push_notes: list[dict] = []
        tombstones: list[dict] = []
        pushed_timestamps: list[tuple[str, int]] = []
        for note_id, deleted, queued_at in outbox:
            local = self.store.get(note_id)
            if local is not None:
                push_notes.append(note_to_dict(local))
                pushed_timestamps.append((note_id, local.updated_at))
            elif deleted:
                tombstones.append({"id": note_id, "deletedAt": queued_at})
            # 本地不存在且未标记删除（异常情况）：直接丢弃该队列项
        queued = [(note_id, queued_at) for note_id, _, queued_at in outbox]

        uploaded = self._uploaded_names()
        uploaded_added: set[str] = set()
        uploaded_reset = False
        rejected_names: set[str] = set()
        applied = 0
        conflicts = 0
        images_up = 0
        images_down = 0
        remote_versions: dict[str, int] = {}
        attachments_supported = True
        current_seq = since_seq
        first_round = True

        # 附件按批传输（每批 ≤8 个 / ≤8MB）：同一次同步内循环，直到无进展或无待办
        for _round in range(8):
            request_notes = push_notes if first_round else []
            request_tombstones = tombstones if first_round else []
            want_batch: list[str] = []
            upload_payload: list[dict] = []
            if attachments_supported:
                referenced, present = attachments.referenced_names(self.store)
                missing = sorted(referenced - present)
                want_batch = missing[: config.MAX_ATTACHMENTS_PER_MESSAGE]
                for name in attachments.take_batch(sorted((referenced - uploaded) & present)):
                    data = attachments.blob_data(name)
                    if data is not None:
                        upload_payload.append(
                            {"name": name, "data": attachments.encode_wire(data)}
                        )
            try:
                payload = self._request(
                    "POST", "/api/sync",
                    {
                        "sinceSeq": current_seq,
                        "notes": request_notes,
                        "tombstones": request_tombstones,
                        "wantAttachments": want_batch,
                        "attachments": upload_payload,
                    },
                )
            except SyncError as exc:
                return SyncResult(False, str(exc))
            first_round = False

            capabilities = payload.get("capabilities") or []
            if "attachments" not in capabilities:
                # 旧版服务端不支持附件字段：停用本端附件逻辑，便签同步保持可用
                attachments_supported = False

            seq = int(payload.get("seq") or 0)
            if seq < current_seq:
                # 服务端计数回退（换库/恢复备份）：改从 0 全量重拉；附件上传记录作废重传
                uploaded.clear()
                uploaded_added.clear()
                uploaded_reset = True
                try:
                    payload = self._request(
                        "POST", "/api/sync", {"sinceSeq": 0, "notes": [], "tombstones": []},
                    )
                except SyncError as exc:
                    return SyncResult(False, str(exc))
                seq = int(payload.get("seq") or 0)
            else:
                seq = seq or current_seq
            current_seq = seq

            remote_notes = payload.get("notes") or []
            round_applied = 0
            for item in remote_notes:
                if not isinstance(item, dict):
                    continue
                remote = note_from_dict(item)
                remote_versions[remote.id] = remote.updated_at
                local = self.store.get(remote.id)
                if local is None:
                    self.store.apply_remote(remote)
                    applied += 1
                    round_applied += 1
                elif remote.updated_at > local.updated_at:
                    self.store.apply_remote(remote)
                    applied += 1
                    round_applied += 1
                elif remote.updated_at == local.updated_at:
                    # 同毫秒平局（协议 v5）：删除优先；同删除态时内容哈希小者胜，
                    # 保证与服务端/P2P 一致的确定性收敛
                    if remote.is_deleted != local.is_deleted:
                        if remote.is_deleted:
                            self.store.apply_remote(remote)
                            applied += 1
                            round_applied += 1
                    else:
                        remote_hash = remote.note_content_hash()
                        local_hash = local.note_content_hash()
                        if remote_hash and remote_hash < local_hash:
                            self.store.apply_remote(remote)
                            applied += 1
                            round_applied += 1
                else:
                    # 本地更新：下轮推送，服务端会以 LWW 接受
                    self.store.enqueue_sync(local.id, local.is_deleted)
                    conflicts += 1

            round_progress = round_applied > 0
            if attachments_supported:
                for item in payload.get("attachments") or []:
                    if not isinstance(item, dict):
                        continue
                    name = str(item.get("name") or "")
                    data = attachments.decode_wire(item.get("data"))
                    if data is not None and attachments.store_blob(name, data):
                        images_down += 1
                        round_progress = True
                for value in payload.get("attachmentsStored") or []:
                    name = str(value or "")
                    if name and name not in uploaded:
                        uploaded.add(name)
                        uploaded_added.add(name)
                        images_up += 1
                        round_progress = True
                for value in payload.get("attachmentsRejected") or []:
                    name = str(value or "")
                    if name:
                        rejected_names.add(name)

            if not attachments_supported or not round_progress:
                break
            referenced, present = attachments.referenced_names(self.store)
            if not (referenced - present) and not ((referenced - uploaded) & present):
                break

        self.store.outbox_clear(queued)
        # 同步期间本地又被修改的便签：重新入队，下一轮再推送
        for note_id, pushed_at in pushed_timestamps:
            local = self.store.get(note_id)
            if local is not None and local.updated_at > max(pushed_at, remote_versions.get(note_id, 0)):
                self.store.enqueue_sync(note_id, local.is_deleted)
        self.store.meta_set(self._last_seq_key(), str(current_seq))
        self.store.meta_set("last_sync_at", str(now_ms()))
        if uploaded_added or uploaded_reset:
            # 服务端计数回退后即使本轮没传成功也要落盘：避免下次同步误以为附件已上传
            self._save_uploaded_names(uploaded)

        pending_images = len(attachments.missing_names(self.store)) if attachments_supported else 0
        message = f"服务器同步完成：推送 {len(push_notes)} 条，拉取 {applied} 条"
        if images_up or images_down:
            message += f"，图片 上传 {images_up}、下载 {images_down}"
        if rejected_names:
            message += f"，{len(rejected_names)} 张图片被服务器拒绝（空间不足等）"
        if pending_images:
            message += f"，{pending_images} 张图片待续传"
        if conflicts:
            message += f"，{conflicts} 条本地新改动待下一轮推送"
        return SyncResult(
            True,
            message,
            pushed=len(push_notes),
            pulled=applied,
            images_uploaded=images_up,
            images_downloaded=images_down,
            images_pending=pending_images,
        )

    def test_connection(self, server_url: str) -> tuple[bool, str]:
        try:
            payload = self._request("GET", "/api/health", auth=False, server_url=server_url, timeout=8)
        except SyncError as exc:
            return False, str(exc)
        version = payload.get("version", "?")
        message = f"连接正常（服务端 v{version}）"
        if _needs_https_hint(normalize_server_url(server_url)):
            message += "；当前为明文 HTTP，公网使用请配置 HTTPS"
        return True, message
