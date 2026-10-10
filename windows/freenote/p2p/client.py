"""同步客户端：向另一台设备发起的两步同步。

1. POST /api/freenote/manifest —— 交换状态清单（id/updatedAt/删除标记/内容哈希 + 摘要）；
   若摘要相同直接结束；否则对方返回需要本机补发的 id 列表；
2. POST /api/freenote/exchange —— 补发对方缺少的便签，同时取回本机缺少的便签，
   双方按 updatedAt 最后写入者获胜（LWW）合并（同毫秒平局：删除优先，
   再按内容哈希确定性仲裁）。
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass
from typing import Callable

import requests

from .. import attachments, config
from ..database import NoteStore
from ..models import note_from_dict
from . import protocol

_TIMEOUT = 20
_USER_AGENT = "FreeNote/1.0"
# 局域网直连：禁用系统/环境代理，避免 Clash 等代理拦截内网同步流量
_SESSION = requests.Session()
_SESSION.trust_env = False


class PeerSyncError(Exception):
    """与对端通信失败（网络、认证、协议）。"""


class PeerAuthError(PeerSyncError):
    """同步口令不一致。"""


class PeerAccountError(PeerSyncError):
    """同步账户不一致（服务器账号或离线账户不同）。"""


class PeerProtocolError(PeerSyncError):
    """对端返回了无法理解的数据。"""


class PeerSessionExpired(PeerProtocolError):
    """同步会话已过期（需要对端重新走一遍清单交换）。"""


@dataclass
class PeerExchangeResult:
    ok: bool = True
    message: str = ""
    peer_device_id: str = ""
    peer_device_name: str = ""
    up_to_date: bool = False
    pushed: int = 0
    pulled: int = 0
    conflicts: int = 0
    peer_digest: str = ""
    images_pushed: int = 0
    images_pulled: int = 0
    images_pending: int = 0


class PeerClient:
    def __init__(
        self,
        store: NoteStore,
        group_key_provider: Callable[[], bytes],
        device_id: str,
        device_name: str,
        listen_port: int = 0,
        account_fingerprint: str = "",
    ):
        self.store = store
        self._group_key_provider = group_key_provider
        self.device_id = device_id
        self.device_name = device_name
        self.listen_port = int(listen_port or 0)
        self.account_fingerprint = account_fingerprint or ""

    # ------------------------------------------------------------------ HTTP

    def _key(self) -> bytes:
        key = self._group_key_provider() or b""
        if not key:
            raise PeerSyncError("尚未设置同步口令")
        return key

    def _request(self, address: str, port: int, path: str, payload: dict) -> dict:
        key = self._key()
        plaintext = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        body = protocol.seal(key, plaintext)
        timestamp = int(time.time() * 1000)
        nonce = protocol.new_nonce()
        headers = {
            "User-Agent": _USER_AGENT,
            "Content-Type": "application/octet-stream",
            protocol.ENVELOPE_HEADER: protocol.ENVELOPE_MODE,
            "X-FreeNote-Device": self.device_id,
            "X-FreeNote-Ts": str(timestamp),
            "X-FreeNote-Nonce": nonce,
            "X-FreeNote-Sig": protocol.sign(key, "POST", path, timestamp, nonce, body),
        }
        if self.account_fingerprint:
            headers["X-FreeNote-Account"] = self.account_fingerprint
        url = f"http://{address}:{int(port)}{path}"
        try:
            response = _SESSION.post(url, data=body, headers=headers, timeout=_TIMEOUT)
        except requests.exceptions.Timeout as exc:
            raise PeerSyncError("连接对端超时") from exc
        except requests.exceptions.RequestException as exc:
            raise PeerSyncError(f"无法连接对端：{exc}") from exc

        if response.status_code == 401:
            raise PeerAuthError("同步口令不一致，已拒绝同步")
        if response.status_code == 403 and _error_code(response) == "account-mismatch":
            raise PeerAccountError(_error_message(response))
        if response.status_code == 409:
            raise PeerSessionExpired("同步会话已过期")
        if response.status_code == 426:
            raise PeerProtocolError(_error_message(response))
        if response.status_code >= 400:
            raise PeerSyncError(_error_message(response))
        self._verify_response(path, response, key)
        return self._decode_response(response, key)

    def _decode_response(self, response: requests.Response, key: bytes) -> dict:
        plain = protocol.unseal(key, response.content)
        if plain is None:
            raise PeerProtocolError("对端响应解密失败")
        try:
            data = json.loads(plain.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as exc:
            raise PeerProtocolError("对端返回了无效数据") from exc
        if not isinstance(data, dict):
            raise PeerProtocolError("对端返回了无效数据")
        return data

    def _verify_response(self, path: str, response: requests.Response, key: bytes) -> None:
        signature = response.headers.get("X-FreeNote-Sig")
        if not signature:
            # 缺少签名说明响应可能被中间人伪造/篡改，一律拒绝
            raise PeerProtocolError("对端响应缺少签名")
        try:
            timestamp = int(response.headers.get("X-FreeNote-Ts") or 0)
        except ValueError:
            raise PeerProtocolError("对端响应签名无效")
        nonce = response.headers.get("X-FreeNote-Nonce") or ""
        if not protocol.verify_signature(key, "RESPONSE", path, timestamp, nonce, response.content, signature):
            raise PeerProtocolError("对端响应签名校验失败")
        # 拒绝重放旧响应（例如把过去的「已是最新」重新放给客户端）
        if abs(int(time.time() * 1000) - timestamp) > config.AUTH_CLOCK_SKEW_MS:
            raise PeerProtocolError("对端响应时间戳无效")

    # ------------------------------------------------------------------ 同步

    def ping(self, address: str, port: int) -> dict:
        url = f"http://{address}:{int(port)}/api/freenote/ping"
        try:
            response = _SESSION.get(url, headers={"User-Agent": _USER_AGENT}, timeout=6)
        except requests.exceptions.RequestException as exc:
            raise PeerSyncError(f"无法连接对端：{exc}") from exc
        if response.status_code >= 400:
            raise PeerSyncError(_error_message(response))
        try:
            return response.json()
        except ValueError as exc:
            raise PeerProtocolError("对端返回了无效数据") from exc

    def sync(self, address: str, port: int) -> PeerExchangeResult:
        for attempt in (1, 2):
            manifest_payload = {
                "protocol": config.PROTOCOL_VERSION,
                "device": protocol.device_payload(self.device_id, self.device_name, self.listen_port),
                "digest": self.store.state_digest(),
                "entries": protocol.entries_payload(self.store),
                "images": protocol.attachment_inventory(),
            }
            manifest_response = self._request(address, port, "/api/freenote/manifest", manifest_payload)
            peer_device = manifest_response.get("device") or {}
            peer_id = str(peer_device.get("id") or "")
            peer_name = str(peer_device.get("name") or "")
            if peer_id and peer_id == self.device_id:
                raise PeerSyncError("检测到与自身设备 ID 相同的对端（请检查是否重复复制了数据目录）")
            if manifest_response.get("upToDate"):
                # 摘要一致说明对端已有同样的条目（含墓碑）：可以安全地把本地墓碑物化为删除行
                self._materialize_tombstones()
                return PeerExchangeResult(
                    ok=True,
                    message="已是最新",
                    peer_device_id=peer_id,
                    peer_device_name=peer_name,
                    up_to_date=True,
                    peer_digest=str(manifest_response.get("digest") or ""),
                )

            session_id = str(manifest_response.get("sessionId") or "")
            if not session_id:
                raise PeerProtocolError("对端未返回同步会话")
            # 附件（协议 v6）：wantImages = 本机引用但缺失、对端清单持有的；
            # push_names = 对端清单声明想要、需要本机推送的
            want_from_peer = protocol.wanted_attachments(
                self.store, manifest_response.get("images")
            )
            push_names = attachments.valid_name_list(manifest_response.get("wantImages"))
            need = [str(x) for x in (manifest_response.get("need") or []) if x]
            notes_out = protocol.collect_notes(self.store, need)

            try:
                exchange = self._request(
                    address,
                    port,
                    "/api/freenote/exchange",
                    {
                        "protocol": config.PROTOCOL_VERSION,
                        "device": protocol.device_payload(self.device_id, self.device_name, self.listen_port),
                        "sessionId": session_id,
                        "notes": notes_out,
                    },
                )
            except PeerSessionExpired:
                if attempt == 1:
                    continue  # 会话过期：本轮清单作废，重来一次
                raise

            result = self._apply_exchange(exchange, peer_id, peer_name, len(notes_out))
            try:
                pushed_images, pulled_images, pending = self._transfer_images(
                    address, port, push_names, want_from_peer
                )
            except PeerSyncError:
                # 便签同步已完成；附件通道故障（如对端磁盘满）不应回滚整体结果
                pushed_images = pulled_images = 0
                pending = len(attachments.missing_names(self.store))
            result.images_pushed = pushed_images
            result.images_pulled = pulled_images
            result.images_pending = pending
            parts = []
            if pushed_images or pulled_images:
                parts.append(f"图片 送出 {pushed_images}、取回 {pulled_images}")
            if pending:
                parts.append(f"{pending} 张图片待续传")
            if parts:
                result.message += "（" + "，".join(parts) + "）"
            return result

        raise PeerProtocolError("同步会话反复过期，请重试")

    def _transfer_images(
        self, address: str, port: int, push_names: list[str], want_names: list[str]
    ) -> tuple[int, int, int]:
        """交换附件：先推送对端要的（image_put），再拉取本机缺的（image_get）。

        每轮调用最多 8 个批次；剩余量由 images_pending 报告，由引擎决定是否续跑。
        返回 (送出, 取回, 本机仍缺)。
        """
        pushed = 0
        pulled = 0
        device = protocol.device_payload(self.device_id, self.device_name, self.listen_port)

        remaining = list(push_names)
        for _ in range(8):
            if not remaining:
                break
            batch = attachments.take_batch(remaining)
            if not batch:
                break
            payload = []
            for name in batch:
                data = attachments.blob_data(name)
                if data is None:
                    continue
                payload.append({"name": name, "data": attachments.encode_wire(data)})
            if not payload:
                break
            response = self._request(
                address, port, "/api/freenote/image_put",
                {"protocol": config.PROTOCOL_VERSION, "device": device, "images": payload},
            )
            stored = [str(x) for x in (response.get("stored") or [])]
            pushed += len(stored)
            sent = {str(item.get("name")) for item in payload}
            remaining = [name for name in remaining if name not in sent]
            if len(stored) < len(payload):
                break  # 对端拒收（空间不足等）：停止本轮推送

        remaining_want = list(want_names)
        for _ in range(8):
            if not remaining_want:
                break
            batch = remaining_want[: config.MAX_ATTACHMENTS_PER_MESSAGE]
            response = self._request(
                address, port, "/api/freenote/image_get",
                {"protocol": config.PROTOCOL_VERSION, "device": device, "names": batch},
            )
            got = 0
            received: set[str] = set()
            for item in response.get("images") or []:
                if not isinstance(item, dict):
                    continue
                name = str(item.get("name") or "")
                data = attachments.decode_wire(item.get("data"))
                if data is not None and attachments.store_blob(name, data):
                    got += 1
                    received.add(name)
            pulled += got
            # 对端明确没有的名（missing）不再要；因字节上限未返回的名保留到下一轮
            missing = {str(x) for x in (response.get("missing") or [])}
            remaining_want = [
                name for name in remaining_want if name not in received and name not in missing
            ]
            if got == 0:
                break  # 无进展：等待下一轮

        pending = len(attachments.missing_names(self.store))
        return pushed, pulled, pending

    def _materialize_tombstones(self) -> None:
        for note_id, deleted_at in self.store.tombstones():
            self.store.materialize_tombstone(note_id, deleted_at)

    def _apply_exchange(
        self,
        exchange: dict,
        peer_id: str,
        peer_name: str,
        pushed: int,
    ) -> PeerExchangeResult:
        pulled = 0
        for item in exchange.get("notes") or []:
            if not isinstance(item, dict):
                continue
            if not protocol.valid_remote_note_entry(item):
                continue
            try:
                note = note_from_dict(item)
            except Exception:  # noqa: BLE001
                continue
            if note.id and self.store.apply_remote_lww(note):
                pulled += 1

        # 已传播的硬删除墓碑：写成正式的删除行（与 FreeNote 服务端语义一致），
        # 保证摘要收敛、后续同步可走快速通道。
        self._materialize_tombstones()

        conflicts = int(exchange.get("conflicts") or 0)
        parts = []
        if pushed:
            parts.append(f"送出 {pushed} 条")
        if pulled:
            parts.append(f"取回 {pulled} 条")
        if conflicts:
            parts.append(f"{conflicts} 条冲突保留较新版本")
        return PeerExchangeResult(
            ok=True,
            message="同步完成" + ("：" + "，".join(parts) if parts else ""),
            peer_device_id=peer_id,
            peer_device_name=peer_name,
            pushed=pushed,
            pulled=pulled,
            conflicts=conflicts,
            peer_digest=str(exchange.get("digest") or ""),
        )


def _error_payload(response: requests.Response) -> dict:
    try:
        payload = response.json()
    except Exception:  # noqa: BLE001
        return {}
    return payload if isinstance(payload, dict) else {}


def _error_code(response: requests.Response) -> str:
    return str(_error_payload(response).get("code") or "")


def _error_message(response: requests.Response) -> str:
    payload = _error_payload(response)
    detail = payload.get("error") or payload.get("detail")
    if detail:
        return str(detail)
    return f"HTTP {response.status_code}"
