"""P2P 同步引擎：把内置服务、局域网发现、同步客户端和已知设备登记簿串起来。

对 UI 只暴露少量接口：
- start() / stop() / restart()
- peers() / add_manual_peer() / forget_peer()
- sync_all() / sync_peer()
- notify_local_change()  —— 本地写入后调用，触发公告与自动同步
事件通过 on_event(name, payload) 回调，UI 侧转成 Qt 信号。
"""

from __future__ import annotations

import socket
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Callable

from .. import config
from ..database import NoteStore
from ..models import now_ms
from ..settings import Settings
from . import protocol
from .client import PeerAccountError, PeerAuthError, PeerClient, PeerExchangeResult, PeerSyncError
from .discovery import Announcement, DiscoveryService
from .server import PeerHttpServer, PeerServerContext

EventCallback = Callable[[str, dict], None]
_ALL_INTERFACES = "0.0.0.0"


def _local_addresses() -> frozenset[str]:
    """本机地址集合（回环 + 各网卡 IP），用于识别指向自己的对端登记。

    模拟器 / Docker / 端口转发环境里，对端连进来的源地址可能呈现为
    本机自己的地址（回环或局域网 IP）；这类「自指路由」不应登记。
    """
    addrs = {"127.0.0.1", "::1", "localhost"}
    try:
        addrs.update(socket.gethostbyname_ex(socket.gethostname())[2])
    except OSError:
        pass
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("8.8.8.8", 80))  # 不发包，仅取默认出口地址
            addrs.add(probe.getsockname()[0])
    except OSError:
        pass
    return frozenset(addrs)


@dataclass
class PeerInfo:
    id: str
    name: str = ""
    address: str = ""
    port: int = config.DEFAULT_SYNC_PORT
    manual: bool = False
    digest: str = ""
    synced_digest: str = ""
    account: str = ""
    last_seen: int = 0
    last_sync_at: int = 0
    last_status: str = ""

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "address": self.address,
            "port": self.port,
            "manual": self.manual,
            "digest": self.digest,
            "synced_digest": self.synced_digest,
            "account": self.account,
            "last_seen": self.last_seen,
            "last_sync_at": self.last_sync_at,
            "last_status": self.last_status,
        }

    @staticmethod
    def from_dict(data: dict) -> "PeerInfo":
        try:
            port = int(data.get("port") or config.DEFAULT_SYNC_PORT)
        except (TypeError, ValueError, OverflowError):
            port = config.DEFAULT_SYNC_PORT
        try:
            last_seen = int(data.get("last_seen") or 0)
        except (TypeError, ValueError, OverflowError):
            last_seen = 0
        try:
            last_sync_at = int(data.get("last_sync_at") or 0)
        except (TypeError, ValueError, OverflowError):
            last_sync_at = 0
        return PeerInfo(
            id=str(data.get("id") or ""),
            name=str(data.get("name") or ""),
            address=str(data.get("address") or ""),
            port=port,
            manual=bool(data.get("manual")),
            digest=str(data.get("digest") or ""),
            synced_digest=str(data.get("synced_digest") or ""),
            account=str(data.get("account") or ""),
            last_seen=last_seen,
            last_sync_at=last_sync_at,
            last_status=str(data.get("last_status") or ""),
        )


@dataclass
class PeerSyncResult:
    ok: bool
    message: str
    peer_id: str = ""
    peer_name: str = ""
    pushed: int = 0
    pulled: int = 0
    conflicts: int = 0
    up_to_date: bool = False
    busy: bool = False
    images_pushed: int = 0
    images_pulled: int = 0
    images_pending: int = 0


@dataclass
class SyncSummary:
    ok: bool
    message: str
    synced: int = 0
    failed: int = 0
    skipped: int = 0
    pushed: int = 0
    pulled: int = 0
    conflicts: int = 0
    images_pushed: int = 0
    images_pulled: int = 0
    errors: list[str] = field(default_factory=list)


def parse_address(text: str) -> tuple[str, int] | None:
    """解析 "主机:端口"（端口可省略，默认 8765）。"""
    value = (text or "").strip()
    if not value:
        return None
    if value.startswith("[") and "]" in value:  # IPv6 字面量
        host, _, rest = value[1:].partition("]")
        port_text = rest.lstrip(":")
        try:
            port = int(port_text) if port_text else config.DEFAULT_SYNC_PORT
        except ValueError:
            return None
        return (host, port) if host else None
    host, sep, port_text = value.rpartition(":")
    if sep and port_text.isdigit():
        port = int(port_text)
    else:
        host, port = value, config.DEFAULT_SYNC_PORT
    if not host or ":" in host or port < 1 or port > 65535:
        return None
    return host, port


class PeerSyncEngine:
    def __init__(self, store: NoteStore, settings: Settings, on_event: EventCallback | None = None):
        self.store = store
        self.settings = settings
        self._on_event = on_event
        self._event_lock = threading.Lock()
        self._lock = threading.RLock()
        self._peers: dict[str, PeerInfo] = {}
        self._peer_locks: dict[str, threading.Lock] = {}
        self._key_cache: tuple[str, str, bytes] | None = None
        self._server: PeerHttpServer | None = None
        self._server_thread: threading.Thread | None = None
        self._discovery: DiscoveryService | None = None
        self._port = 0
        self._executor: ThreadPoolExecutor | None = None
        self._queued: set[str] = set()
        self._last_attempt: dict[str, float] = {}
        self._debounce: threading.Timer | None = None
        self._stopped = False
        self._persist_at = 0.0
        self._local_addrs = _local_addresses()
        self._load_peers()

    # ------------------------------------------------------------------ 生命周期

    @property
    def running(self) -> bool:
        return self._server is not None

    @property
    def port(self) -> int:
        return self._port or self.settings.sync_port

    @property
    def discovery_running(self) -> bool:
        discovery = self._discovery
        return bool(discovery and discovery.running)

    def _ensure_executor(self) -> None:
        with self._lock:
            if self._executor is None:
                self._executor = ThreadPoolExecutor(
                    max_workers=2, thread_name_prefix="freenote-sync"
                )

    def start(self) -> bool:
        self._ensure_executor()
        self._warm_group_key()
        with self._lock:
            self._stopped = False
            if self._server is not None:
                return True
            if not self.settings.p2p_enabled or not self.settings.p2p_ready():
                # 网络功能默认关闭：未在设置中开启「启用 P2P 同步」（且配置口令）前
                # 不监听端口、不参与发现，保持完全离线
                return True
            if not self._start_server():
                return False
            self._prune_peers_for_account()
            if self.settings.discovery_enabled:
                self._start_discovery()
            if self.group_key():
                self.announce_now()
            self._emit("status", {"text": f"P2P 服务已启动（端口 {self._port}）"})
            return True

    def stop(self) -> None:
        with self._lock:
            was_running = self._server is not None or self._discovery is not None
            self._stopped = True
            self._persist_peers(force=True)
            discovery, self._discovery = self._discovery, None
            server, self._server = self._server, None
            thread, self._server_thread = self._server_thread, None
            executor, self._executor = self._executor, None
            # cancel_futures 会跳过排队任务的 finally，清空避免重启后公告同步永久失效
            self._queued.clear()
        if executor is not None:
            try:
                executor.shutdown(wait=False, cancel_futures=True)
            except Exception:  # noqa: BLE001
                pass
        # 停止动作放在锁外：发现回调可能正持有引擎锁，锁内 join 会白等超时
        if discovery is not None:
            discovery.stop()
        if server is not None:
            try:
                server.shutdown()
                server.server_close()
            except Exception:  # noqa: BLE001
                pass
        if thread is not None and thread.is_alive():
            thread.join(timeout=3)
        if was_running:
            self._emit("status", {"text": "P2P 服务已停止"})

    def restart(self) -> bool:
        self.stop()
        return self.start()

    def _start_server(self) -> bool:
        context = PeerServerContext(
            store=self.store,
            group_key_provider=self.group_key,
            device_id=self.settings.device_id,
            device_name=self.settings.device_name,
            on_sync=self._on_remote_sync,
            account_fingerprint_provider=self.account_fingerprint,
        )
        base = self.settings.sync_port or 0
        candidates = [base] if base > 0 else [0]
        if base > 0:
            candidates += [base + offset for offset in range(1, 11)] + [0]
        for candidate in candidates:
            try:
                server = PeerHttpServer((_ALL_INTERFACES, candidate), context)
            except OSError:
                continue
            self._server = server
            self._port = int(server.server_address[1])
            self._server_thread = threading.Thread(
                target=server.serve_forever, name="freenote-server", daemon=True
            )
            self._server_thread.start()
            return True
        self._emit("error", {"text": "无法启动 P2P 服务：端口被占用"})
        return False

    def _start_discovery(self) -> None:
        discovery = DiscoveryService(
            announce_provider=self._announcement,
            tag_provider=self._tag,
            on_peer=self._on_announced,
        )
        discovery.set_self_id(self.settings.device_id)
        self._discovery = discovery
        if not discovery.start():
            self._emit("error", {"text": "局域网发现不可用（UDP 端口被占用），可使用手动添加设备"})

    # ------------------------------------------------------------------ 密钥与公告

    def account_namespace(self) -> str:
        """本机同步账户：优先服务器账号（地址 + 用户名），否则离线账户名。"""
        return protocol.account_namespace(
            self.settings.server_url,
            self.settings.server_username,
            self.settings.p2p_account,
        )

    def account_fingerprint(self) -> str:
        return protocol.account_fingerprint(self.account_namespace())

    def group_key(self) -> bytes:
        passphrase = self.settings.group_passphrase
        namespace = self.account_namespace()
        with self._lock:
            if (
                self._key_cache
                and self._key_cache[0] == passphrase
                and self._key_cache[1] == namespace
            ):
                return self._key_cache[2]
            key = protocol.derive_group_key(passphrase, namespace) if passphrase else b""
            self._key_cache = (passphrase, namespace, key)
            return key

    def _warm_group_key(self) -> None:
        """后台预热口令派生缓存：PBKDF2 耗时数百毫秒，避免首次本地修改卡顿 UI。"""
        executor = self._executor
        if executor is None:
            return
        try:
            executor.submit(self.group_key)
        except RuntimeError:
            pass

    def _tag(self) -> str:
        key = self.group_key()
        return protocol.group_tag(key) if key else ""

    def _announcement(self) -> Announcement | None:
        if not self.group_key():
            return None
        port = self._port or self.settings.sync_port
        if not port:
            return None
        try:
            digest = self.store.state_digest()
        except Exception:  # noqa: BLE001
            digest = ""
        return Announcement(
            device_id=self.settings.device_id,
            name=self.settings.device_name,
            port=port,
            digest=digest,
        )

    def announce_now(self) -> None:
        """请求尽快公告（交由发现线程执行，避免在调用线程计算状态摘要）。"""
        discovery = self._discovery
        if discovery is not None:
            discovery.request_announce()

    def notify_local_change(self) -> None:
        """本地写入后调用：立即公告，并按需触发一轮自动同步。"""
        self.announce_now()
        if not (self.settings.auto_sync and self.group_key()):
            return
        with self._lock:
            if self._debounce is not None:
                self._debounce.cancel()
            self._debounce = threading.Timer(2.5, self._debounced_sync_all)
            self._debounce.daemon = True
            self._debounce.start()

    def account_changed(self) -> None:
        """同步账户变化（登录/退出服务器、修改离线账户）后调用。

        旧账户的设备条目不再可用：直接清理，避免自动同步反复失败刷屏；
        新账户的设备会在下次公告/发现后重新登记。
        """
        removed = self._prune_peers_for_account()
        if removed:
            self._emit("peers", {})
        self.announce_now()

    def _debounced_sync_all(self) -> None:
        with self._lock:
            self._debounce = None
            if self._stopped:
                return
        self.submit_sync_all()

    def submit_sync_all(self) -> None:
        """在后台线程执行一轮与所有设备的同步（不阻塞 UI）。"""
        executor = self._executor
        if executor is None:
            return
        try:
            executor.submit(self._safe_sync_all)
        except RuntimeError:
            pass

    def _safe_sync_all(self) -> None:
        try:
            if self._stopped:
                return
            summary = self.sync_all()
        except Exception as exc:  # noqa: BLE001
            summary = SyncSummary(False, f"同步失败：{exc}")
        self._emit("summary", {"summary": summary})

    # ------------------------------------------------------------------ 设备登记

    def peers(self) -> list[PeerInfo]:
        fingerprint = self.account_fingerprint()
        with self._lock:
            peers = [p for p in self._peers.values() if p.account == fingerprint]
        return sorted(peers, key=lambda p: (not p.manual, (p.name or p.address).casefold()))

    def _load_peers(self) -> None:
        fingerprint = self.account_fingerprint()
        for data in self.settings.peers():
            peer = PeerInfo.from_dict(data)
            if peer.id and peer.account == fingerprint:
                self._peers[peer.id] = peer

    def _prune_peers_for_account(self) -> int:
        """丢弃属于其他账户的设备条目（切换账户后设备列表按新账户重建）。"""
        fingerprint = self.account_fingerprint()
        with self._lock:
            stale = [peer_id for peer_id, peer in self._peers.items() if peer.account != fingerprint]
            for peer_id in stale:
                del self._peers[peer_id]
                self._peer_locks.pop(peer_id, None)
                self._last_attempt.pop(peer_id, None)
                self._queued.discard(peer_id)
        if stale:
            self._persist_peers(force=True)
        return len(stale)

    def _persist_peers(self, force: bool = False) -> None:
        with self._lock:
            if not force and time.monotonic() - self._persist_at < 15.0:
                return
            self._persist_at = time.monotonic()
            snapshot = [p.to_dict() for p in self._peers.values()]
        try:
            self.settings.set_peers(snapshot)
        except Exception:  # noqa: BLE001
            pass

    def _peer_lock(self, peer_id: str) -> threading.Lock:
        with self._lock:
            lock = self._peer_locks.get(peer_id)
            if lock is None:
                lock = threading.Lock()
                self._peer_locks[peer_id] = lock
            return lock

    def _upsert_peer(
        self,
        peer_id: str,
        name: str = "",
        address: str = "",
        port: int = 0,
        digest: str = "",
        manual: bool | None = None,
    ) -> PeerInfo:
        fingerprint = self.account_fingerprint()
        with self._lock:
            peer = self._peers.get(peer_id)
            if peer is None:
                peer = PeerInfo(id=peer_id, manual=bool(manual))
                self._peers[peer_id] = peer
            if name:
                peer.name = name
            if address:
                peer.address = address
            if port:
                peer.port = int(port)
            if digest:
                peer.digest = digest
            if manual is not None:
                peer.manual = manual
            peer.account = fingerprint
            peer.last_seen = now_ms()
            return peer

    def _rekey_peer(self, old_id: str, new_id: str, name: str = "") -> str:
        """手动添加的设备在学习到真实设备 ID 后归并到正式条目。"""
        if not new_id or new_id == old_id:
            return old_id
        with self._lock:
            peer = self._peers.pop(old_id, None)
            if peer is None:
                return new_id
            existing = self._peers.pop(new_id, None)
            peer.id = new_id
            if name:
                peer.name = name
            if existing is not None:
                peer.manual = peer.manual and existing.manual
                peer.digest = peer.digest or existing.digest
                peer.synced_digest = existing.synced_digest or peer.synced_digest
                peer.last_seen = max(peer.last_seen, existing.last_seen)
                peer.last_sync_at = max(peer.last_sync_at, existing.last_sync_at)
                peer.last_status = existing.last_status or peer.last_status
            self._peers[new_id] = peer
            lock = self._peer_locks.pop(old_id, None)
            if lock is not None:
                self._peer_locks[new_id] = lock
            last_attempt = self._last_attempt.pop(old_id, None)
            if last_attempt is not None:
                self._last_attempt[new_id] = last_attempt
            if old_id in self._queued:
                self._queued.discard(old_id)
                self._queued.add(new_id)
            return new_id

    def add_manual_peer(self, text: str) -> tuple[bool, str]:
        parsed = parse_address(text)
        if parsed is None:
            return False, "地址格式无效，请使用 主机:端口（例如 192.168.1.8:8765）"
        host, port = parsed
        if port == (self._port or self.settings.sync_port) and host in ("127.0.0.1", "localhost", "::1"):
            return False, "不能添加本机作为同步设备"
        peer_id = f"manual:{host}:{port}"
        fingerprint = self.account_fingerprint()
        client = PeerClient(
            self.store,
            self.group_key,
            self.settings.device_id,
            self.settings.device_name,
            account_fingerprint=fingerprint,
        )
        try:
            payload = client.ping(host, port)
        except PeerSyncError as exc:
            return False, f"无法连接 {host}:{port}：{exc}"
        if payload.get("app") != "freenote":
            return False, "该地址不是 FreeNote 设备"
        if str(payload.get("account") or "") != fingerprint:
            return False, (
                "对方设备属于另一个同步账户：请确认两台设备登录了同一服务器账号，"
                "或填写相同的离线账户"
            )
        self._upsert_peer(peer_id, address=host, port=port, manual=True)
        self._persist_peers(force=True)
        self._emit("peers", {})
        return True, f"已添加设备 {host}:{port}，点击「立即同步」开始同步"

    def forget_peer(self, peer_id: str) -> None:
        with self._lock:
            self._peers.pop(peer_id, None)
            self._peer_locks.pop(peer_id, None)
            self._last_attempt.pop(peer_id, None)
            self._queued.discard(peer_id)
        self._persist_peers(force=True)
        self._emit("peers", {})

    # ------------------------------------------------------------------ 远端活动

    def _is_self_route(self, address: str, port: int) -> bool:
        """地址+端口是否指向本机自己的监听端口（NAT 环回场景）。"""
        if not address or port <= 0:
            return False
        if port != (self._port or self.settings.sync_port):
            return False
        return address.lower() in self._local_addrs

    def _on_remote_sync(
        self,
        device_id: str,
        device_name: str,
        address: str,
        device_port: int,
        applied: int,
        conflicts: int,
    ) -> None:
        """对端主动同步过来时，登记它的信息（并把同地址+端口的手动占位条目并入真实身份）。

        两类登记不能覆盖已有地址：
        - 手动添加的地址是用户显式配置的可用路由；
        - 「自指路由」（对端经 NAT/端口转发连入，源地址呈现为本机自己）。
        """
        if not device_id or device_id == self.settings.device_id:
            return
        self._merge_address_placeholder(device_id, address, device_port)
        self_route = self._is_self_route(address, device_port)
        with self._lock:
            existing = self._peers.get(device_id)
        if self_route and existing is None:
            # 指向自己的新登记只会产生自同步失败，不创建
            return
        if self_route or (existing is not None and existing.manual):
            peer = self._upsert_peer(device_id, name=device_name)
        else:
            peer = self._upsert_peer(device_id, name=device_name, address=address or "")
        if applied or conflicts:
            peer.last_sync_at = now_ms()
            peer.last_status = f"对端合并 {applied} 条"
            self._persist_peers()
        self._emit("peers", {})

    def _merge_address_placeholder(self, device_id: str, address: str, device_port: int) -> None:
        """把与真实设备 地址+端口 完全一致的手动占位条目并入正式条目。

        必须同时比较端口：多台设备可能共享同一个 IP（回环、NAT、同机多实例）。
        """
        if not address or device_port <= 0:
            return
        fingerprint = self.account_fingerprint()
        with self._lock:
            for key in list(self._peers.keys()):
                placeholder = self._peers.get(key)
                if placeholder is None or key == device_id or not key.startswith("manual:"):
                    continue
                if placeholder.address != address or placeholder.port != device_port:
                    continue
                existing = self._peers.get(device_id)
                del self._peers[key]
                self._peer_locks.pop(key, None)
                if existing is None:
                    placeholder.id = device_id
                    placeholder.account = fingerprint
                    self._peers[device_id] = placeholder
                else:
                    existing.manual = existing.manual or placeholder.manual
                    existing.digest = existing.digest or placeholder.digest
                    existing.account = fingerprint
                    existing.last_seen = max(existing.last_seen, placeholder.last_seen)
                break

    # ------------------------------------------------------------------ 同步执行

    def sync_all(self) -> SyncSummary:
        if self._stopped:
            return SyncSummary(False, "P2P 服务已停止")
        if not self.group_key():
            return SyncSummary(False, "尚未设置同步口令：请在「设置 → P2P 同步」中填写")
        peers = self._unique_peers()
        if not peers:
            return SyncSummary(
                False, "暂无可同步的设备：等待局域网发现，或在设置中手动添加 地址:端口"
            )
        synced = failed = skipped = pushed = pulled = conflicts = 0
        images_pushed = images_pulled = 0
        errors: list[str] = []
        for peer in peers:
            if self._stopped:
                break
            try:
                result = self._sync_peer_locked(peer.id, wait_for_lock=True)
            except Exception as exc:  # noqa: BLE001 - 单台设备异常不影响其它设备
                failed += 1
                errors.append(f"{peer.name or peer.address}: {exc}")
                continue
            if result.ok:
                synced += 1
                pushed += result.pushed
                pulled += result.pulled
                conflicts += result.conflicts
                images_pushed += result.images_pushed
                images_pulled += result.images_pulled
            elif result.busy:
                skipped += 1
            else:
                failed += 1
                errors.append(f"{peer.name or peer.address}: {result.message}")
        parts = [f"已同步 {synced}/{len(peers)} 台设备"]
        if pushed:
            parts.append(f"送出 {pushed} 条")
        if pulled:
            parts.append(f"取回 {pulled} 条")
        if images_pushed or images_pulled:
            parts.append(f"图片 送出 {images_pushed}、取回 {images_pulled}")
        if conflicts:
            parts.append(f"{conflicts} 条冲突保留较新版本")
        if skipped:
            parts.append(f"{skipped} 台正在同步，已跳过")
        if failed:
            parts.append(f"{failed} 台失败")
        return SyncSummary(
            ok=synced > 0 or (failed == 0 and skipped > 0),
            message="；".join(parts),
            synced=synced,
            failed=failed,
            skipped=skipped,
            pushed=pushed,
            pulled=pulled,
            conflicts=conflicts,
            images_pushed=images_pushed,
            images_pulled=images_pulled,
            errors=errors,
        )

    def _unique_peers(self) -> list[PeerInfo]:
        """按 地址:端口 去重：同一台设备可能同时被手动添加和被自动发现。"""
        peers: list[PeerInfo] = []
        seen: set[tuple[str, int]] = set()
        for peer in self.peers():
            key = (peer.address, peer.port)
            if key in seen:
                continue
            seen.add(key)
            peers.append(peer)
        return peers

    def sync_peer(self, peer_id: str) -> PeerSyncResult:
        if not self.group_key():
            return PeerSyncResult(False, "尚未设置同步口令")
        return self._sync_peer_locked(peer_id, wait_for_lock=True)

    def _sync_peer_locked(self, peer_id: str, wait_for_lock: bool = False) -> PeerSyncResult:
        with self._lock:
            peer = self._peers.get(peer_id)
        if peer is None:
            return PeerSyncResult(False, "设备不存在", peer_id=peer_id)
        if not peer.address or not peer.port:
            return PeerSyncResult(False, "设备地址缺失", peer_id=peer_id, peer_name=peer.name)
        if peer.account != self.account_fingerprint():
            return PeerSyncResult(
                False, "该设备属于其他同步账户，需重新发现或添加",
                peer_id=peer_id, peer_name=peer.name,
            )
        lock = self._peer_lock(peer_id)
        if wait_for_lock:
            acquired = lock.acquire(timeout=25)
        else:
            acquired = lock.acquire(blocking=False)
        if not acquired:
            return PeerSyncResult(
                False, "该设备正在同步中", peer_id=peer_id, peer_name=peer.name, busy=True
            )
        try:
            self._last_attempt[peer_id] = time.monotonic()
            client = PeerClient(
                self.store,
                self.group_key,
                self.settings.device_id,
                self.settings.device_name,
                listen_port=self._port or self.settings.sync_port,
                account_fingerprint=self.account_fingerprint(),
            )
            try:
                result: PeerExchangeResult = client.sync(peer.address, peer.port)
            except PeerAuthError:
                self._update_peer_status(peer_id, "同步口令不一致，已拒绝")
                return PeerSyncResult(
                    False, "同步口令不一致，请确认两台设备使用相同的同步口令",
                    peer_id=peer_id, peer_name=peer.name,
                )
            except PeerAccountError as exc:
                self._update_peer_status(peer_id, "同步账户不一致，已拒绝")
                return PeerSyncResult(False, str(exc), peer_id=peer_id, peer_name=peer.name)
            except PeerSyncError as exc:
                self._update_peer_status(peer_id, f"失败：{exc}")
                return PeerSyncResult(False, str(exc), peer_id=peer_id, peer_name=peer.name)
            except Exception as exc:  # noqa: BLE001 - 网络之外的意外错误也要回落到结果对象
                self._update_peer_status(peer_id, f"失败：{exc}")
                return PeerSyncResult(False, f"同步失败：{exc}", peer_id=peer_id, peer_name=peer.name)

            new_peer_id = self._rekey_peer(peer_id, result.peer_device_id, result.peer_device_name)
            with self._lock:
                current = self._peers.get(new_peer_id)
                if current is not None:
                    current.address = peer.address
                    current.port = peer.port
                    current.last_sync_at = now_ms()
                    current.last_seen = now_ms()
                    current.synced_digest = self.store.state_digest()
                    current.digest = result.peer_digest or current.digest
                    current.last_status = "同步完成" if not result.up_to_date else "已是最新"
                    if result.peer_device_name:
                        current.name = current.name or result.peer_device_name
            self._persist_peers()
            outcome = PeerSyncResult(
                ok=True,
                message=result.message,
                peer_id=new_peer_id,
                peer_name=result.peer_device_name,
                pushed=result.pushed,
                pulled=result.pulled,
                conflicts=result.conflicts,
                up_to_date=result.up_to_date,
                images_pushed=result.images_pushed,
                images_pulled=result.images_pulled,
                images_pending=result.images_pending,
            )
            self._emit("peers", {})
            self._emit("sync_result", {"result": outcome})
            if not result.up_to_date:
                self.announce_now()
            if result.images_pending > 0 and (result.images_pushed + result.images_pulled) > 0:
                # 附件按批传输：仍有待续传且有进展时，短延迟后自动续跑一轮
                timer = threading.Timer(1.5, self._trigger_sync, args=(new_peer_id,))
                timer.daemon = True
                timer.start()
            return outcome
        finally:
            lock.release()

    def _update_peer_status(self, peer_id: str, status: str) -> None:
        with self._lock:
            peer = self._peers.get(peer_id)
            if peer is not None:
                peer.last_status = status
        self._persist_peers()
        self._emit("peers", {})

    # ------------------------------------------------------------------ 发现回调

    def _on_announced(self, announcement: Announcement, address: str, source_port: int) -> None:
        if not self.group_key():
            return
        # 经 NAT/端口转发环回的公告：源地址是本机自己 + 本机监听端口，
        # 不能用它登记新设备，也不能覆盖已有条目（尤其是手动）的可用地址
        self_route = self._is_self_route(address, announcement.port)
        with self._lock:
            existing = self._peers.get(announcement.device_id)
        if self_route and existing is None:
            return
        if self_route or (existing is not None and existing.manual):
            self._upsert_peer(
                announcement.device_id,
                name=announcement.name,
                digest=announcement.digest,
            )
        else:
            self._upsert_peer(
                announcement.device_id,
                name=announcement.name,
                address=address,
                port=announcement.port,
                digest=announcement.digest,
            )
        self._persist_peers()
        self._emit("peers", {})
        if not self.settings.auto_sync:
            return
        last = self._last_attempt.get(announcement.device_id, 0.0)
        if time.monotonic() - last < config.SYNC_COOLDOWN_SEC:
            return  # 冷却期内不触发同步，也无需重算全库摘要
        local_digest = self.store.state_digest()
        if announcement.digest and announcement.digest == local_digest:
            return  # 双方状态一致，无需同步
        self._trigger_sync(announcement.device_id)

    def _trigger_sync(self, peer_id: str) -> None:
        with self._lock:
            if peer_id in self._queued or self._stopped:
                return
            executor = self._executor
            if executor is None:
                return
            self._queued.add(peer_id)
        try:
            executor.submit(self._run_queued_sync, peer_id)
        except RuntimeError:
            with self._lock:
                self._queued.discard(peer_id)

    def _run_queued_sync(self, peer_id: str) -> None:
        try:
            with self._lock:
                if self._stopped:
                    return
            self._sync_peer_locked(peer_id)
        except Exception:  # noqa: BLE001
            pass
        finally:
            with self._lock:
                self._queued.discard(peer_id)

    # ------------------------------------------------------------------ 事件

    def set_event_callback(self, callback: EventCallback | None) -> None:
        with self._event_lock:
            self._on_event = callback

    def _emit(self, name: str, payload: dict) -> None:
        with self._event_lock:
            callback = self._on_event
        if callback is None:
            return
        try:
            callback(name, payload)
        except Exception:  # noqa: BLE001
            pass
