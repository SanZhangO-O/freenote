"""内置的 P2P 同步服务：每台设备监听一个 HTTP 端口，接受同口令设备的直接同步。

端点：
- GET  /api/freenote/ping     健康检查（无需认证，返回协议版本与账户指纹）
- POST /api/freenote/manifest 交换状态清单，返回对方需要什么 / 是否已一致
- POST /api/freenote/exchange 推送对方缺少的便签，并取回自己缺少的便签

所有 POST 请求都要求 HMAC 签名（见 protocol.py）；账户指纹不一致或未设置同步口令
一律拒绝，避免同一局域网内不同账户的数据互相串。
"""

from __future__ import annotations

import json
import socket
import socketserver
import threading
import time
import uuid
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable

from .. import attachments, config
from ..database import NoteStore
from ..models import note_from_dict
from . import protocol

_PING_PATH = "/api/freenote/ping"
_MANIFEST_PATH = "/api/freenote/manifest"
_EXCHANGE_PATH = "/api/freenote/exchange"
_IMAGE_GET_PATH = "/api/freenote/image_get"
_IMAGE_PUT_PATH = "/api/freenote/image_put"
_MAX_BODY = 32 * 1024 * 1024


class SyncSessionStore:
    """manifest 往返期间的短期会话（内存保存，一次性消费）。"""

    def __init__(self, ttl_sec: float = config.MANIFEST_SESSION_TTL_SEC):
        self._ttl_ms = int(ttl_sec * 1000)
        self._sessions: dict[str, dict] = {}
        self._lock = threading.Lock()

    def create(self, device_id: str, manifest: dict[str, tuple[int, bool]]) -> str:
        session_id = uuid.uuid4().hex
        now = int(time.time() * 1000)
        with self._lock:
            self._purge_locked(now)
            self._sessions[session_id] = {"device_id": device_id, "manifest": manifest, "created": now}
        return session_id

    def pop(self, session_id: str) -> dict | None:
        if not session_id:
            return None
        now = int(time.time() * 1000)
        with self._lock:
            self._purge_locked(now)
            return self._sessions.pop(session_id, None)

    def _purge_locked(self, now: int) -> None:
        expired = [k for k, s in self._sessions.items() if now - s["created"] > self._ttl_ms]
        for key in expired:
            del self._sessions[key]


@dataclass
class PeerServerContext:
    """服务器运行所需的外部依赖。"""

    store: NoteStore
    group_key_provider: Callable[[], bytes]
    device_id: str
    device_name: str
    on_sync: Callable[[str, str, str, int, int, int], None] | None = None
    """on_sync(device_id, device_name, address, listen_port, applied, conflicts) —— 供引擎登记对端信息。"""
    account_fingerprint_provider: Callable[[], str] | None = None
    """account_fingerprint() —— 本机同步账户指纹（空串 = 未设置账户）。"""

    def group_key(self) -> bytes:
        try:
            return self.group_key_provider() or b""
        except Exception:  # noqa: BLE001
            return b""

    def account_fingerprint(self) -> str:
        provider = self.account_fingerprint_provider
        if provider is None:
            return ""
        try:
            return provider() or ""
        except Exception:  # noqa: BLE001
            return ""


class PeerHttpServer(ThreadingHTTPServer):
    daemon_threads = True
    # Windows 上 SO_REUSEADDR 允许两个进程绑定同一端口并把连接“抢走”，
    # 关掉它：第二个实例会绑定失败并自动顺延端口，行为可预期。
    allow_reuse_address = False

    def __init__(self, address: tuple[str, int], context: PeerServerContext):
        self.context = context
        self.sessions = SyncSessionStore()
        self.nonce_cache = protocol.NonceCache()
        super().__init__(address, _PeerRequestHandler)

    def server_bind(self) -> None:
        # 跳过 BaseHTTPServer 的 getfqdn()：绑定 0.0.0.0 时反向 DNS 解析可能耗时数秒
        socketserver.TCPServer.server_bind(self)
        host, port = self.server_address[:2]
        self.server_name = host
        self.server_port = port


class _PeerRequestHandler(BaseHTTPRequestHandler):
    server_version = "FreeNote/1.0"
    protocol_version = "HTTP/1.1"
    # 半开连接时读/写最多阻塞的时长（socketserver 在 setup 里 settimeout）。
    # 按次收发计时而非整个请求计：32MB 大负载只要数据仍在流动就不会误断，
    # 取 15s 让服务端先于客户端超时（客户端 _TIMEOUT=20），及时释放 handler 线程。
    timeout = 15

    # ------------------------------------------------------------------ 基础

    def log_message(self, *args) -> None:  # noqa: D102 - 静默，避免刷屏
        pass

    def handle_one_request(self) -> None:
        try:
            super().handle_one_request()
        except socket.timeout:
            # keep-alive 间隙或对端消失导致读超时：按正常关闭处理，不刷异常栈
            self.close_connection = True

    @property
    def context(self) -> PeerServerContext:
        return self.server.context  # type: ignore[attr-defined]

    @property
    def sessions(self) -> SyncSessionStore:
        return self.server.sessions  # type: ignore[attr-defined]

    def do_GET(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        if path == _PING_PATH:
            self._send_json(
                200,
                {
                    "app": "freenote",
                    "version": 1,
                    "protocol": config.PROTOCOL_VERSION,
                    "account": self.context.account_fingerprint(),
                },
            )
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        body = self._read_body()
        if body is None:
            return
        key = self.context.group_key()
        if not key:
            self._send_json(403, {"error": "对方未设置同步口令"})
            return
        peer_account = (self.headers.get("X-FreeNote-Account") or "").strip()
        if peer_account != self.context.account_fingerprint():
            # 同一局域网内的不同账户禁止互通；错误响应不带密钥，对端可直接读出原因
            self._send_json(
                403,
                {
                    "error": "双方同步账户不一致：请确认两台设备登录了同一服务器账号，或设置相同的离线账户",
                    "code": "account-mismatch",
                },
            )
            return
        if not self._verify_request(path, body, key):
            self._send_json(401, {"error": "认证失败：同步口令不一致或请求被篡改"})
            return
        plain = protocol.unseal(key, body)
        if plain is None:
            self._send_json(400, {"error": "请求未加密或解密失败，请升级客户端"})
            return
        try:
            payload = json.loads(plain.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self._send_json(400, {"error": "请求不是有效的 JSON"})
            return
        if not isinstance(payload, dict):
            self._send_json(400, {"error": "请求格式无效"})
            return

        if payload.get("protocol") != config.PROTOCOL_VERSION:
            # 426 Upgrade Required：让对方给出明确的「需要升级」提示，而不是按会话过期重试
            self._send_json(426, {"error": "协议版本不一致，请升级 FreeNote"})
            return

        try:
            if path == _MANIFEST_PATH:
                self._handle_manifest(payload, key)
            elif path == _EXCHANGE_PATH:
                self._handle_exchange(payload, key)
            elif path == _IMAGE_GET_PATH:
                self._handle_image_get(payload, key)
            elif path == _IMAGE_PUT_PATH:
                self._handle_image_put(payload, key)
            else:
                self._send_json(404, {"error": "not found"})
        except Exception:  # noqa: BLE001 - 任何内部异常都转成 500，避免连接悬挂
            self.close_connection = True
            self._send_json(500, {"error": "对方内部错误"})

    # ------------------------------------------------------------------ 读写

    def _read_body(self) -> bytes | None:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self.close_connection = True
            self._send_json(400, {"error": "Content-Length 无效"})
            return None
        if length <= 0 or length > _MAX_BODY:
            self.close_connection = True  # 避免 keep-alive 连接上残留未读的请求体
            self._send_json(400, {"error": "请求体为空或过大"})
            return None
        try:
            return self.rfile.read(length)
        except OSError:
            self.close_connection = True
            return None

    def _send_json(self, status: int, payload: dict, key: bytes | None = None) -> None:
        plain = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        # 带密钥的业务响应（清单/便签）先加密再签名，签名覆盖密文；
        # 错误响应无密钥，保持明文以便对端直接读出错误原因
        body = protocol.seal(key, plain) if key else plain
        self.send_response(status)
        if key:
            self.send_header("Content-Type", "application/octet-stream")
        else:
            self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        if key:
            timestamp = int(time.time() * 1000)
            nonce = protocol.new_nonce()
            # 响应签名覆盖的路径必须与客户端校验路径一致：统一为去 query 的路径
            self.send_header(protocol.ENVELOPE_HEADER, protocol.ENVELOPE_MODE)
            self.send_header("X-FreeNote-Ts", str(timestamp))
            self.send_header("X-FreeNote-Nonce", nonce)
            self.send_header(
                "X-FreeNote-Sig",
                protocol.sign(
                    key, "RESPONSE", self.path.split("?", 1)[0], timestamp, nonce, body
                ),
            )
        self.end_headers()
        try:
            self.wfile.write(body)
        except OSError:
            pass

    # ------------------------------------------------------------------ 认证

    def _verify_request(self, path: str, body: bytes, key: bytes) -> bool:
        try:
            timestamp = int(self.headers.get("X-FreeNote-Ts") or 0)
        except ValueError:
            return False
        nonce = self.headers.get("X-FreeNote-Nonce") or ""
        signature = self.headers.get("X-FreeNote-Sig") or ""
        now = int(time.time() * 1000)
        if abs(now - timestamp) > config.AUTH_CLOCK_SKEW_MS:
            return False
        if not (8 <= len(nonce) <= 64):
            return False
        if not protocol.verify_signature(key, "POST", path, timestamp, nonce, body, signature):
            return False
        return self.sessions_accept_nonce(nonce, now)

    def sessions_accept_nonce(self, nonce: str, now: int) -> bool:
        return self.server.nonce_cache.accept(nonce, now)  # type: ignore[attr-defined]

    # ------------------------------------------------------------------ 业务

    def _device_of(self, payload: dict) -> tuple[str, str, int]:
        device = payload.get("device") or {}
        if not isinstance(device, dict):
            return "", "", 0
        try:
            port = int(device.get("port") or 0)
        except (TypeError, ValueError):
            port = 0
        return str(device.get("id") or ""), str(device.get("name") or ""), port

    def _handle_manifest(self, payload: dict, key: bytes) -> None:
        store = self.context.store
        device_id, device_name, device_port = self._device_of(payload)
        peer_manifest = protocol.manifest_from_entries(payload.get("entries"))

        local_digest = store.state_digest()
        remote_digest = str(payload.get("digest") or "")
        up_to_date = bool(remote_digest) and remote_digest == local_digest
        response: dict = {
            "protocol": config.PROTOCOL_VERSION,
            "device": protocol.device_payload(self.context.device_id, self.context.device_name),
            "digest": local_digest,
            "upToDate": up_to_date,
        }
        if not up_to_date:
            # 附件清单（协议 v6）：双方交换本机持有的附件名；
            # wantImages = 本机引用但缺失、且对方清单持有的附件（由对方在交换后推送）
            response["images"] = protocol.attachment_inventory()
            response["wantImages"] = protocol.wanted_attachments(store, payload.get("images"))
            # 需要对方补发的：对方清单里有、且按 LWW 应覆盖本机版本
            local_versions = {
                note_id: (updated_at, deleted, content_hash)
                for note_id, updated_at, deleted, content_hash in store.sync_entries()
            }
            need = [
                note_id
                for note_id, incoming in peer_manifest.items()
                if protocol.incoming_wins(local_versions.get(note_id), incoming)
            ]
            session_id = self.sessions.create(device_id, peer_manifest)
            response["sessionId"] = session_id
            response["need"] = need
        self._notify_sync(device_id, device_name, self._remote_address(), device_port, 0, 0)
        self._send_json(200, response, key)

    def _handle_exchange(self, payload: dict, key: bytes) -> None:
        store = self.context.store
        device_id, device_name, device_port = self._device_of(payload)
        session = self.sessions.pop(str(payload.get("sessionId") or ""))
        if session is None:
            self._send_json(409, {"error": "同步会话已过期，请重试"})
            return

        applied = conflicts = 0
        notes_in = payload.get("notes") or []
        if isinstance(notes_in, list):
            for item in notes_in:
                if not isinstance(item, dict):
                    continue
                if not protocol.valid_remote_note_entry(item):
                    continue
                try:
                    note = note_from_dict(item)
                except Exception:  # noqa: BLE001
                    continue
                if store.apply_remote_lww(note):
                    applied += 1
                else:
                    conflicts += 1

        out = protocol.notes_peer_lacks(store, session["manifest"])
        response = {
            "protocol": config.PROTOCOL_VERSION,
            "device": protocol.device_payload(self.context.device_id, self.context.device_name),
            "digest": store.state_digest(),
            "applied": applied,
            "conflicts": conflicts,
            "notes": out,
        }
        self._notify_sync(device_id, device_name, self._remote_address(), device_port, applied, conflicts)
        self._send_json(200, response, key)

    def _handle_image_get(self, payload: dict, key: bytes) -> None:
        """按名取附件：返回请求列表中本机持有的附件（数量/字节双上限）。"""
        names = attachments.valid_name_list(payload.get("names"))
        images: list[dict] = []
        missing: list[str] = []
        total = 0
        more = False
        for name in names:
            data = attachments.blob_data(name)
            if data is None:
                missing.append(name)
                continue
            if (
                len(images) >= config.MAX_ATTACHMENTS_PER_MESSAGE
                or total + len(data) > config.MAX_ATTACHMENT_BYTES_PER_MESSAGE
            ):
                more = True
                # 上限之后的名称本轮不处理：既不算缺失也不返回，由 more 标记提示客户端下轮继续
                break
            images.append({"name": name, "data": attachments.encode_wire(data)})
            total += len(data)
        response = {
            "protocol": config.PROTOCOL_VERSION,
            "images": images,
            "missing": missing,
            "more": more,
        }
        self._send_json(200, response, key)

    def _handle_image_put(self, payload: dict, key: bytes) -> None:
        """收附件：写入本机附件目录（名称必须与内容哈希一致，双上限约束）。"""
        raw_images = payload.get("images")
        stored: list[str] = []
        rejected: list[str] = []
        total = 0
        if isinstance(raw_images, list):
            for item in raw_images[: config.MAX_ATTACHMENTS_PER_MESSAGE * 2]:
                if not isinstance(item, dict):
                    continue
                name = str(item.get("name") or "")
                data = attachments.decode_wire(item.get("data"))
                if (
                    data is None
                    or len(stored) >= config.MAX_ATTACHMENTS_PER_MESSAGE
                    or total + len(data) > config.MAX_ATTACHMENT_BYTES_PER_MESSAGE
                ):
                    rejected.append(name or "?")
                    continue
                if attachments.store_blob(name, data):
                    stored.append(name)
                    total += len(data)
                else:
                    rejected.append(name or "?")
        response = {
            "protocol": config.PROTOCOL_VERSION,
            "stored": stored,
            "rejected": rejected,
        }
        self._send_json(200, response, key)

    def _remote_address(self) -> str:
        return self.client_address[0] if self.client_address else ""

    def _notify_sync(
        self, device_id: str, device_name: str, address: str, device_port: int, applied: int, conflicts: int
    ) -> None:
        callback = self.context.on_sync
        if callback is None:
            return
        try:
            callback(device_id, device_name, address, device_port, applied, conflicts)
        except Exception:  # noqa: BLE001 - 回调异常不影响协议处理
            pass
