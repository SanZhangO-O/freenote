"""P2P 协议工具：账户命名空间、组密钥派生、请求签名、正文加密、摘要与便签收集。

安全模型（无需中心服务器）：
- 每台设备属于一个「同步账户」：配置了服务器账号（地址 + 用户名）时用服务器账号，
  否则用设置里的「离线账户」；两端账户不同则连组密钥都不同，发现不到也解不开；
- 每台设备设置同一个「同步口令」，同一账户下双方各自推导出 32 字节组密钥；
- 请求/响应正文用组密钥做 AES-256-GCM 加密（保密性 + 完整性）；
- 每个 HTTP 请求额外附带 时间戳 + 随机数 + HMAC-SHA256 签名（对密文签名），
  时间戳偏差超过 5 分钟或随机数重复（重放）一律拒绝；
- 局域网发现广播使用「账户 + 口令」派生的 tag，不同账户/口令的设备互相不可见。
"""

from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import threading
import time

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .. import config
from ..database import NoteStore
from ..models import note_to_dict, tombstone_note

_GROUP_SALT = b"freenote-group-v1"
_ANNOUNCE_CONTEXT = b"freenote-announce-v1"
_ACCOUNT_SALT_CONTEXT = b"|freenote-account-v1|"
_ACCOUNT_HASH_CONTEXT = b"freenote-account-hash-v1\n"

# 正文加密（AES-256-GCM）：密文 = nonce(12) + ciphertext + tag(16)
ENVELOPE_HEADER = "X-FreeNote-Enc"
ENVELOPE_MODE = "aesgcm-v1"
_ENVELOPE_NONCE_BYTES = 12
_ENVELOPE_TAG_BYTES = 16
_NOTE_ID_MAX_LEN = 128


def seal_with_nonce(key: bytes, nonce: bytes, plaintext: bytes) -> bytes:
    """用指定 nonce 加密正文（跨语言测试向量用；生产路径始终使用随机 nonce）。"""
    return nonce + AESGCM(key).encrypt(nonce, plaintext, None)


def seal(key: bytes, plaintext: bytes) -> bytes:
    """用组密钥加密正文；每次加密使用新的随机 nonce。"""
    nonce = os.urandom(_ENVELOPE_NONCE_BYTES)
    return seal_with_nonce(key, nonce, plaintext)


def unseal(key: bytes, blob: bytes) -> bytes | None:
    """解密正文；密钥不符或数据被篡改（GCM 校验失败）返回 None。"""
    if len(blob) <= _ENVELOPE_NONCE_BYTES + _ENVELOPE_TAG_BYTES:
        return None
    try:
        return AESGCM(key).decrypt(blob[:_ENVELOPE_NONCE_BYTES], blob[_ENVELOPE_NONCE_BYTES:], None)
    except Exception:  # noqa: BLE001 - 解密失败一律视为无效数据
        return None


def account_namespace(server_url: str, username: str, local_account: str) -> str:
    """同步账户命名空间：配置了服务器账号时用「服务器 + 用户名」，否则用离线账户名。

    两端必须得到完全相同的字符串才能同组；返回空串表示未设置账户
    （此时同一口令即同组，建议在设置中配置账户）。
    """
    url = (server_url or "").strip().rstrip("/").lower()
    user = (username or "").strip()
    if url and user:
        return f"server:{url}#{user}"
    local = (local_account or "").strip()
    return f"local:{local}" if local else ""


def account_fingerprint(namespace: str) -> str:
    """账户指纹：可随发现/健康检查公开的短哈希，用于提示两端账户是否一致。"""
    if not namespace:
        return ""
    return hashlib.sha256(_ACCOUNT_HASH_CONTEXT + namespace.encode("utf-8")).hexdigest()[:16]


def derive_group_key(passphrase: str, account: str = "") -> bytes:
    """组密钥：账户非空时并入 PBKDF2 salt，不同账户即使口令相同也互不可见。"""
    salt = _GROUP_SALT
    if account:
        salt += _ACCOUNT_SALT_CONTEXT + account.encode("utf-8")
    return hashlib.pbkdf2_hmac(
        "sha256",
        passphrase.encode("utf-8"),
        salt,
        config.GROUP_KEY_ITERATIONS,
        32,
    )


def group_tag(key: bytes) -> str:
    """发现广播的匹配标签：只有口令相同的设备才能算出同样的 tag。"""
    return hmac.new(key, _ANNOUNCE_CONTEXT, hashlib.sha256).hexdigest()[:16]


def body_hash(body: bytes) -> str:
    return hashlib.sha256(body).hexdigest()


def canonical(method: str, path: str, timestamp: int, nonce: str, body: bytes) -> bytes:
    return f"{method}\n{path}\n{timestamp}\n{nonce}\n{body_hash(body)}".encode("utf-8")


def sign(key: bytes, method: str, path: str, timestamp: int, nonce: str, body: bytes) -> str:
    return hmac.new(key, canonical(method, path, timestamp, nonce, body), hashlib.sha256).hexdigest()


def new_nonce() -> str:
    return secrets.token_hex(16)


def verify_signature(
    key: bytes, method: str, path: str, timestamp: int, nonce: str, body: bytes, signature: str
) -> bool:
    expected = sign(key, method, path, timestamp, nonce, body)
    return hmac.compare_digest(expected, signature or "")


class NonceCache:
    """短期随机数缓存，防止重放攻击。"""

    def __init__(self, ttl_ms: int = config.AUTH_CLOCK_SKEW_MS):
        self._ttl_ms = ttl_ms
        self._entries: dict[str, int] = {}
        self._lock = threading.Lock()

    def accept(self, nonce: str, now: int | None = None) -> bool:
        now = now if now is not None else int(time.time() * 1000)
        with self._lock:
            if len(self._entries) > 4096:
                self._purge_locked(now)
            if nonce in self._entries:
                return False
            self._entries[nonce] = now + self._ttl_ms
            return True

    def _purge_locked(self, now: int) -> None:
        expired = [n for n, expires in self._entries.items() if expires <= now]
        for nonce in expired:
            del self._entries[nonce]


def device_payload(device_id: str, device_name: str, listen_port: int = 0) -> dict:
    return {"id": device_id, "name": device_name, "port": int(listen_port or 0)}


def collect_notes(store: NoteStore, ids: list[str]) -> list[dict]:
    """按 id 收集整条便签（含尚未物化的墓碑），用于回应对端的 need 列表。"""
    tomb_map = dict(store.tombstones())
    notes: list[dict] = []
    for note_id in ids:
        if not note_id:
            continue
        note = store.get(note_id)
        if note is not None:
            notes.append(note_to_dict(note))
        elif note_id in tomb_map:
            notes.append(note_to_dict(tombstone_note(note_id, tomb_map[note_id])))
    return notes


def notes_peer_lacks(store: NoteStore, peer_manifest: dict[str, tuple[int, bool, str]]) -> list[dict]:
    """本机有、而对方没有或应被本机版本覆盖的全部便签（含墓碑）。"""
    tomb_map = dict(store.tombstones())
    notes: list[dict] = []
    for note_id, updated_at, deleted, content_hash in store.sync_entries():
        if not incoming_wins(peer_manifest.get(note_id), (updated_at, deleted, content_hash)):
            continue
        note = store.get(note_id)
        if note is not None:
            notes.append(note_to_dict(note))
        elif note_id in tomb_map:
            notes.append(note_to_dict(tombstone_note(note_id, tomb_map[note_id])))
    return notes


def entries_payload(store: NoteStore) -> list[dict]:
    return [
        {
            "id": note_id,
            "updatedAt": updated_at,
            "isDeleted": deleted,
            "hash": content_hash,
        }
        for note_id, updated_at, deleted, content_hash in store.sync_entries()
    ]


def valid_remote_note_id(value) -> bool:
    """远端传入的便签 id：非空短字符串，不含 ':'、换行/回车或控制字符。"""
    if not isinstance(value, str) or not value or len(value) > _NOTE_ID_MAX_LEN:
        return False
    return all(ch >= " " and ch != "\x7f" and ch != ":" for ch in value)


# ---------------------------------------------------------------- 附件清单（协议 v6）


def attachment_inventory() -> list[str]:
    """本机附件目录中的全部合法附件名（排序、限长），用于与对端交换清单。"""
    from .. import attachments

    return sorted(attachments.local_names())[: config.ATTACHMENT_INVENTORY_LIMIT]


def wanted_attachments(store, peer_inventory_raw) -> list[str]:
    """本机引用了、本地还没有、且对端清单声称拥有的附件名（排序稳定）。"""
    from .. import attachments

    peer_names = set(attachments.valid_name_list(peer_inventory_raw))
    if not peer_names:
        return []
    referenced, present = attachments.referenced_names(store)
    return sorted(name for name in (referenced - present) if name in peer_names)


_INT64_MAX = 2**63 - 1


def _valid_updated_at(value) -> bool:
    """updatedAt 必须可转 int 且落在 int64 内，超界值会让 SQLite 绑定抛 OverflowError。"""
    if isinstance(value, bool) or value is None:
        return False
    try:
        updated = int(value)
    except (TypeError, ValueError, OverflowError):
        return False
    return -_INT64_MAX - 1 <= updated <= _INT64_MAX


def valid_remote_note_entry(item: dict) -> bool:
    """远端便签条目的原始校验：id 与 updatedAt 必须合法，畸形条目由调用方拒收。

    note_from_dict 会对缺失 id 合成随机 uuid、对非法 updatedAt 回落当前时间，
    直接放行会产生幽灵便签并让伪最新版本赢得 LWW。
    """
    return valid_remote_note_id(item.get("id")) and _valid_updated_at(item.get("updatedAt"))


def manifest_from_entries(raw) -> dict[str, tuple[int, bool, str]]:
    """把清单条目解析为 {id: (updatedAt, isDeleted, contentHash)}。"""
    manifest: dict[str, tuple[int, bool, str]] = {}
    if not isinstance(raw, list):
        return manifest
    for item in raw:
        if not isinstance(item, dict):
            continue
        note_id = item.get("id")
        if not valid_remote_note_id(note_id):
            continue
        if not _valid_updated_at(item.get("updatedAt")):
            continue
        manifest[note_id] = (
            int(item.get("updatedAt")),
            bool(item.get("isDeleted")),
            str(item.get("hash") or ""),
        )
    return manifest


def incoming_wins(existing: tuple[int, bool, str] | None, incoming: tuple[int, bool, str]) -> bool:
    """LWW 判定：incoming 是否应覆盖 existing。

    - 时间戳不同：新者胜；
    - 时间戳相同、删除标记不同：删除优先（删除不被同样旧的编辑复活）；
    - 时间戳与删除标记都相同：内容哈希小者胜（确定性仲裁），
      保证「两端同一毫秒编辑出不同内容」也能收敛到同一版本。
    两端都使用同一规则才能保证收敛；哈希缺失（旧清单）时保守保留本机版本。
    """
    if existing is None:
        return True
    if incoming[0] != existing[0]:
        return incoming[0] > existing[0]
    if incoming[1] != existing[1]:
        return incoming[1] and not existing[1]
    existing_hash = existing[2] or ""
    incoming_hash = incoming[2] or ""
    return bool(existing_hash) and bool(incoming_hash) and incoming_hash < existing_hash
