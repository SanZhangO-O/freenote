"""P2P 测试：真实节点通过本机 HTTP 直连同步（可选的组播发现）。

运行： python -m unittest discover -s tests -v
"""

from __future__ import annotations

import json
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

_TMP = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
os.environ["FREENOTE_DATA_DIR"] = str(Path(_TMP.name) / "app-data")

import requests  # noqa: E402

from freenote import config, crypto  # noqa: E402
from freenote.database import NoteStore  # noqa: E402
from freenote.models import Note, content_hash_fields, new_id, now_ms, tombstone_note  # noqa: E402
from freenote.p2p import protocol  # noqa: E402
from freenote.p2p.client import (  # noqa: E402
    PeerAccountError,
    PeerAuthError,
    PeerClient,
    PeerProtocolError,
)
from freenote.p2p.discovery import Announcement, DiscoveryService  # noqa: E402
from freenote.p2p.engine import PeerSyncEngine, parse_address  # noqa: E402
from freenote.p2p.server import PeerHttpServer, PeerServerContext  # noqa: E402
from freenote.settings import Settings  # noqa: E402

PASSPHRASE = "E2E-TEST-CODE-1234"
_MANIFEST_PATH = "/api/freenote/manifest"


def _manifest_payload(protocol_version: int = config.PROTOCOL_VERSION) -> dict:
    return {
        "protocol": protocol_version,
        "device": {"id": "x", "name": "x"},
        "digest": "",
        "entries": [],
    }


def _sealed_request(
    path: str, payload: dict, key: bytes, timestamp_ms: int | None = None
) -> tuple[bytes, dict]:
    """构造一个真实的已加密+已签名请求（用于测试服务端的处理路径）。"""
    body = protocol.seal(key, json.dumps(payload).encode("utf-8"))
    timestamp = timestamp_ms if timestamp_ms is not None else int(time.time() * 1000)
    nonce = protocol.new_nonce()
    headers = {
        "Content-Type": "application/octet-stream",
        protocol.ENVELOPE_HEADER: protocol.ENVELOPE_MODE,
        "X-FreeNote-Device": "test-device",
        "X-FreeNote-Ts": str(timestamp),
        "X-FreeNote-Nonce": nonce,
        "X-FreeNote-Sig": protocol.sign(key, "POST", path, timestamp, nonce, body),
    }
    return body, headers


def _free_tcp_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _free_udp_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _serve(handler) -> tuple[object, threading.Thread]:
    from http.server import ThreadingHTTPServer

    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread


class _Node:
    """一台设备的完整运行实例：存储 + 设置 + 引擎。"""

    def __init__(self, root: Path, name: str):
        self.store = NoteStore(root / f"{name}.db")
        self.settings = Settings(root / f"{name}.json")
        self.settings.device_name = name
        self.settings.group_passphrase = PASSPHRASE
        self.settings.p2p_enabled = True
        self.settings.discovery_enabled = False
        self.settings.auto_sync = False
        self.settings.sync_port = 0
        self.engine = PeerSyncEngine(self.store, self.settings)

    def start(self) -> None:
        assert self.engine.start(), "引擎启动失败"
        assert self.engine._port > 0  # noqa: SLF001 - 测试内部检查

    @property
    def port(self) -> int:
        return self.engine.port

    def address(self) -> str:
        return f"127.0.0.1:{self.engine.port}"

    def close(self) -> None:
        self.engine.stop()
        self.store.close()


class EnginePairTest(unittest.TestCase):
    def setUp(self) -> None:
        suffix = str(time.time_ns())
        self.root = Path(_TMP.name) / f"nodes_{suffix}"
        self.root.mkdir(parents=True, exist_ok=True)
        self.a = _Node(self.root, "node-a")
        self.b = _Node(self.root, "node-b")
        self.a.start()
        self.b.start()
        ok, message = self.a.engine.add_manual_peer(self.b.address())
        self.assertTrue(ok, message)
        ok, message = self.b.engine.add_manual_peer(self.a.address())
        self.assertTrue(ok, message)

    def tearDown(self) -> None:
        self.a.close()
        self.b.close()

    # ------------------------------------------------------------------ 基本同步

    def test_sync_roundtrip_peer_registry_and_batch(self) -> None:
        note = self.a.store.save(Note(title="来自A", content="hello"))
        summary = self.a.engine.sync_all()
        self.assertTrue(summary.ok, summary.message)
        self.assertEqual(summary.pushed, 1)
        pulled = self.b.store.get(note.id)
        self.assertIsNotNone(pulled)
        self.assertEqual(pulled.title, "来自A")

        # B 修改后回传
        pulled.title = "B 改过"
        self.b.store.save(pulled)
        summary = self.b.engine.sync_all()
        self.assertTrue(summary.ok, summary.message)
        self.assertEqual(self.a.store.get(note.id).title, "B 改过")
        self.assertEqual(self.a.store.state_digest(), self.b.store.state_digest())

        # 手动占位条目已换成真实设备 ID；摘要一致时重复同步走快速路径
        peer = self.a.engine.peers()[0]
        self.assertFalse(peer.id.startswith("manual:"))
        self.assertEqual(peer.name, "node-b")
        result = self.a.engine.sync_peer(peer.id)
        self.assertTrue(result.ok, result.message)
        self.assertTrue(result.up_to_date)

        # 批量便签与标签
        for index in range(8):
            self.a.store.save(Note(title=f"便签 {index}", content=f"内容 {index}", tags="批量"))
        summary = self.a.engine.sync_all()
        self.assertEqual(summary.pushed, 8)
        self.assertEqual(len(self.b.store.list_active()), 9)
        self.assertEqual(self.b.store.all_tags(), ["批量"])

        # 回归：本地无改动时也必须拉到对方的新改动（不能因摘要快速跳过）
        b_note = self.b.store.get(note.id)
        b_note.title = "v2 来自 B"
        self.b.store.save(b_note)
        summary = self.a.engine.sync_all()
        self.assertTrue(summary.ok, summary.message)
        self.assertEqual(summary.pulled, 1)
        self.assertEqual(self.a.store.get(note.id).title, "v2 来自 B")

    def test_delete_tombstone_and_lww_conflicts(self) -> None:
        # 移入回收站传播
        note = self.a.store.save(Note(title="将被删除"))
        self.a.engine.sync_all()
        self.b.engine.sync_all()
        self.assertFalse(self.b.store.get(note.id).is_deleted)
        self.a.store.move_to_trash(note.id)
        self.a.engine.notify_local_change()
        self.a.engine.sync_all()
        self.b.engine.sync_all()
        self.assertTrue(self.b.store.get(note.id).is_deleted)

        # 硬删除（清空回收站）→ 墓碑传播，双方摘要一致且无未传播墓碑
        self.a.store.empty_trash()
        self.assertIsNone(self.a.store.get(note.id))
        self.a.engine.sync_all()
        self.b.engine.sync_all()
        self.assertTrue(self.b.store.get(note.id).is_deleted)
        self.assertEqual(self.a.store.state_digest(), self.b.store.state_digest())
        self.assertEqual(self.a.store.tombstones(), [])

        # 离线编辑：较新的编辑胜出
        offline_id = new_id()
        self.a.store.apply_remote(Note(id=offline_id, title="base", updated_at=1_000_000))
        self.a.engine.sync_all()
        self.b.engine.sync_all()
        a_note = self.a.store.get(offline_id)
        a_note.title = "A 编辑（较早）"
        a_note.updated_at = 2_000_000
        self.a.store.apply_remote(a_note)
        b_note = self.b.store.get(offline_id)
        b_note.title = "B 编辑（较新）"
        b_note.updated_at = 3_000_000
        self.b.store.apply_remote(b_note)
        self.b.engine.sync_all()
        self.a.engine.sync_all()
        self.assertEqual(self.a.store.get(offline_id).title, "B 编辑（较新）")
        self.assertEqual(self.b.store.get(offline_id).title, "B 编辑（较新）")

        # 墓碑输给更新的编辑：便签复活
        revive = self.a.store.save(Note(title="v1"))
        self.a.engine.sync_all()
        self.b.engine.sync_all()
        b_note = self.b.store.get(revive.id)
        b_note.title = "B 的新版本"
        b_note.updated_at = now_ms() + 60_000
        self.b.store.apply_remote(b_note)
        self.a.store.move_to_trash(revive.id)
        self.a.store.empty_trash()
        self.assertTrue(self.a.engine.sync_all().ok)
        pulled = self.a.store.get(revive.id)
        self.assertIsNotNone(pulled)
        self.assertFalse(pulled.is_deleted)
        self.assertEqual(pulled.title, "B 的新版本")
        self.b.engine.sync_all()
        self.assertFalse(self.b.store.get(revive.id).is_deleted)

        # 同一毫秒删除 vs 编辑：两个方向都必须收敛到删除态
        def force_deleted(store: NoteStore, note_id: str, stamp: int) -> None:
            with store._lock:  # noqa: SLF001 - 测试直接拨动时间戳/删除标记
                store._conn.execute(
                    "UPDATE notes SET isDeleted = 1, deletedAt = ?, updatedAt = ? WHERE id = ?",
                    (stamp, stamp, note_id),
                )
                store._conn.commit()

        for index, deletion_first in enumerate((True, False)):
            tie = self.a.store.save(Note(title=f"tie-{index}"))
            self.a.engine.sync_all()
            self.b.engine.sync_all()
            shared = tie.updated_at + 10_000 * (index + 1)
            force_deleted(self.a.store, tie.id, shared)
            b_tie = self.b.store.get(tie.id)
            b_tie.title = f"B 同时编辑 {index}"
            b_tie.updated_at = shared
            self.b.store.apply_remote(b_tie)
            engine = self.a.engine if deletion_first else self.b.engine
            self.assertTrue(engine.sync_all().ok)
            self.assertTrue(self.b.store.get(tie.id).is_deleted)
            self.assertTrue(self.a.store.get(tie.id).is_deleted)
            self.assertEqual(self.a.store.state_digest(), self.b.store.state_digest())

        # 同一毫秒两边编辑出不同内容：按内容哈希确定性收敛（与到达顺序无关）
        for index, a_first in enumerate((True, False)):
            tie = self.a.store.save(Note(title=f"edit-tie-{index}"))
            self.a.engine.sync_all()
            self.b.engine.sync_all()
            shared = tie.updated_at + 20_000 * (index + 1)
            a_edit = self.a.store.get(tie.id)
            a_edit.title = "A 同时编辑（内容甲）"
            a_edit.updated_at = shared
            self.a.store.apply_remote(a_edit)
            b_edit = self.b.store.get(tie.id)
            b_edit.title = "B 同时编辑（内容乙）"
            b_edit.updated_at = shared
            self.b.store.apply_remote(b_edit)
            first, second = (self.a.engine, self.b.engine) if a_first else (self.b.engine, self.a.engine)
            self.assertTrue(first.sync_all().ok)
            self.assertTrue(second.sync_all().ok)
            self.assertEqual(
                self.a.store.state_digest(),
                self.b.store.state_digest(),
                "同毫秒双向编辑必须收敛到一致摘要",
            )
            winner_title = self.a.store.get(tie.id).title
            self.assertEqual(self.b.store.get(tie.id).title, winner_title)
            self.assertIn(winner_title, ("A 同时编辑（内容甲）", "B 同时编辑（内容乙）"))

    def test_locked_note_and_encrypted_wire(self) -> None:
        # 锁定便签在传输中保持密文
        cipher = crypto.encrypt("机密内容", "1234")
        note = self.a.store.save(
            Note(title=crypto.encrypt("机密标题", "1234"), content=cipher, is_locked=True)
        )
        self.a.engine.sync_all()
        pulled = self.b.store.get(note.id)
        self.assertTrue(pulled.is_locked)
        self.assertEqual(pulled.content, cipher)
        self.assertNotIn("机密内容", pulled.content)

        # 记录代理验证网络上的请求/响应正文都是 AES-GCM 密文
        real_port = self.b.port
        captured: list[bytes] = []

        from http.server import BaseHTTPRequestHandler

        class _RecordingHandler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):  # noqa: D102
                pass

            def _forward(self, method: str) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length) if length else b""
                captured.append(body)
                forwarded = requests.request(
                    method,
                    f"http://127.0.0.1:{real_port}{self.path}",
                    data=body if method == "POST" else None,
                    headers={
                        k: v
                        for k, v in self.headers.items()
                        if k.lower() not in ("host", "content-length", "connection")
                    },
                    timeout=10,
                )
                captured.append(forwarded.content)
                self.send_response(forwarded.status_code)
                for name, value in forwarded.headers.items():
                    if name.lower() not in ("content-length", "connection", "transfer-encoding"):
                        self.send_header(name, value)
                self.send_header("Content-Length", str(len(forwarded.content)))
                self.end_headers()
                self.wfile.write(forwarded.content)

            def do_GET(self):  # noqa: N802, D102
                self._forward("GET")

            def do_POST(self):  # noqa: N802, D102
                self._forward("POST")

        stub, thread = _serve(_RecordingHandler)
        try:
            plain = self.a.store.save(Note(title="明文标题-绝密", content="明文正文-绝密"))
            client = PeerClient(
                self.a.store, lambda: protocol.derive_group_key(PASSPHRASE), "dev-a", "dev-a"
            )
            result = client.sync("127.0.0.1", int(stub.server_address[1]))
            self.assertTrue(result.ok, result.message)
            self.assertTrue(captured)
            blob = b"".join(captured)
            self.assertNotIn("绝密".encode("utf-8"), blob)
            self.assertNotIn(b'"title"', blob)
            self.assertNotIn(b'"protocol"', blob)
            self.assertIsNotNone(self.b.store.get(plain.id))
        finally:
            stub.shutdown()
            stub.server_close()
            thread.join(timeout=5)

    def test_convergence_scenarios(self) -> None:
        # 双方同时发起同步也必须收敛
        self.a.store.save(Note(title="A 的便签", content="来自 A"))
        self.b.store.save(Note(title="B 的便签", content="来自 B"))
        errors: list[str] = []

        def run(engine) -> None:
            try:
                engine.sync_all()
            except Exception as exc:  # noqa: BLE001
                errors.append(str(exc))

        threads = [
            threading.Thread(target=run, args=(self.a.engine,)),
            threading.Thread(target=run, args=(self.b.engine,)),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=60)
        self.assertEqual(errors, [])
        titles_a = {n.title for n in self.a.store.list_active()}
        self.assertEqual(titles_a, {"A 的便签", "B 的便签"})
        self.assertEqual({n.title for n in self.b.store.list_active()}, titles_a)

        # 随机增删改 + 交替同步：两节点最终完全一致（少量步数即可覆盖状态机）
        import random

        rng = random.Random(20260913)
        known: list[str] = []
        for step in range(12):
            store = self.a.store if rng.random() < 0.5 else self.b.store
            action = rng.choice(["create", "create", "edit", "edit", "trash", "purge", "restore"])
            if action == "create" or not known:
                known.append(store.save(Note(title=f"随机 {step}")).id)
            elif action == "edit":
                note = store.get(rng.choice(known))
                if note is not None and not note.is_deleted:
                    note.title = f"编辑 {step}"
                    note.content = f"content-{step}"
                    store.save(note)
            elif action == "trash":
                note = store.get(rng.choice(known))
                if note is not None and not note.is_deleted:
                    store.move_to_trash(note.id)
            elif action == "purge":
                trashed = store.list_trash()
                if trashed:
                    store.delete_hard(rng.choice(trashed).id)
            elif action == "restore":
                trashed = store.list_trash()
                if trashed:
                    store.restore(rng.choice(trashed).id)
            time.sleep(0.002)  # 避免同一毫秒的并列时间戳（LWW 依赖毫秒时间戳）
            if step % 5 == 4:
                self.a.engine.sync_all()
                self.b.engine.sync_all()
        self.a.engine.sync_all()
        self.b.engine.sync_all()
        self.assertEqual(
            {tuple(e) for e in self.a.store.sync_entries()},
            {tuple(e) for e in self.b.store.sync_entries()},
        )
        self.assertEqual(self.a.store.state_digest(), self.b.store.state_digest())

        # 链式拓扑 A—B—C：A 的改动应在两轮内经 B 传到 C
        c = _Node(self.root, "node-c")
        c.start()
        try:
            ok, message = self.b.engine.add_manual_peer(c.address())
            self.assertTrue(ok, message)
            ok, message = c.engine.add_manual_peer(self.b.address())
            self.assertTrue(ok, message)
            chain = self.a.store.save(Note(title="链式传播"))
            self.a.engine.sync_all()
            for _ in range(2):
                self.b.engine.sync_all()
            self.assertEqual(c.store.get(chain.id).title, "链式传播")
            self.a.store.move_to_trash(chain.id)
            self.a.engine.sync_all()
            for _ in range(2):
                self.b.engine.sync_all()
            self.assertTrue(c.store.get(chain.id).is_deleted)
        finally:
            c.close()

        # 同一设备既有发现条目又有手动条目时，一轮同步只连一次
        ok, message = self.a.engine.add_manual_peer(self.b.address())
        self.assertTrue(ok, message)
        self.assertGreaterEqual(len(self.a.engine.peers()), 2)
        summary = self.a.engine.sync_all()
        self.assertTrue(summary.ok, summary.message)
        self.assertEqual((summary.synced, summary.failed), (1, 0))

    # ------------------------------------------------------------------ 认证与输入防线

    def test_auth_replay_and_input_guards(self) -> None:
        key = protocol.derive_group_key(PASSPHRASE)
        url = f"http://127.0.0.1:{self.b.port}{_MANIFEST_PATH}"

        # 未加密的正文即便签名正确也被拒绝
        body = json.dumps(_manifest_payload()).encode("utf-8")
        timestamp = int(time.time() * 1000)
        nonce = protocol.new_nonce()
        response = requests.post(
            url,
            data=body,
            headers={
                "Content-Type": "application/json",
                "X-FreeNote-Ts": str(timestamp),
                "X-FreeNote-Nonce": nonce,
                "X-FreeNote-Sig": protocol.sign(key, "POST", _MANIFEST_PATH, timestamp, nonce, body),
            },
            timeout=10,
        )
        self.assertEqual(response.status_code, 400)

        # 时间戳过期拒绝
        old_body, old_headers = _sealed_request(
            _MANIFEST_PATH, _manifest_payload(), key,
            timestamp_ms=int(time.time() * 1000) - 10 * 60 * 1000,
        )
        self.assertEqual(
            requests.post(url, data=old_body, headers=old_headers, timeout=10).status_code, 401
        )

        # 重放（相同 nonce）拒绝；业务响应本身也是密文
        body, headers = _sealed_request(_MANIFEST_PATH, _manifest_payload(), key)
        first = requests.post(url, data=body, headers=headers, timeout=10)
        self.assertEqual(first.status_code, 200)
        self.assertEqual(first.headers.get(protocol.ENVELOPE_HEADER), protocol.ENVELOPE_MODE)
        self.assertNotIn(b'"protocol"', first.content)
        self.assertEqual(requests.post(url, data=body, headers=headers, timeout=10).status_code, 401)

        # 协议版本不符返回 426
        mismatch_body, mismatch_headers = _sealed_request(
            _MANIFEST_PATH, _manifest_payload(protocol_version=99), key
        )
        self.assertEqual(
            requests.post(url, data=mismatch_body, headers=mismatch_headers, timeout=10).status_code,
            426,
        )

        # 错误口令：ping 不要求认证，但同步请求被拒（引擎按需派生密钥，无需重启）
        ok, message = self.a.engine.add_manual_peer(self.b.address())
        self.assertTrue(ok, message)
        attacker = PeerClient(
            self.a.store, lambda: protocol.derive_group_key("WRONG"), "attacker", "attacker"
        )
        with self.assertRaises(PeerAuthError):
            attacker.sync("127.0.0.1", self.b.port)
        self.a.settings.group_passphrase = "WRONG-CODE"
        self.a.store.save(Note(title="hello"))
        summary = self.a.engine.sync_all()
        self.assertFalse(summary.ok)
        self.assertTrue(any("口令" in err for err in summary.errors), summary.errors)
        self.a.settings.group_passphrase = PASSPHRASE

        # 无签名的响应被拒绝（防中间人伪造）
        from http.server import BaseHTTPRequestHandler

        class _StubHandler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):  # noqa: D102
                pass

            def do_POST(self):  # noqa: N802, D102
                payload = json.dumps({"protocol": 1, "device": {"id": "fake", "name": "fake"}}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        stub, thread = _serve(_StubHandler)
        try:
            client = PeerClient(
                self.a.store, lambda: protocol.derive_group_key(PASSPHRASE), "dev", "dev"
            )
            with self.assertRaises(PeerProtocolError):
                client.sync("127.0.0.1", int(stub.server_address[1]))
        finally:
            stub.shutdown()
            stub.server_close()
            thread.join(timeout=5)

        # 防线保留：空密钥服务下请求被 403 拒绝
        context = PeerServerContext(
            store=self.a.store,
            group_key_provider=lambda: b"",
            device_id="dev-empty-key",
            device_name="空密钥设备",
        )
        server = PeerHttpServer(("127.0.0.1", 0), context)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            response = requests.post(
                f"http://127.0.0.1:{server.server_address[1]}{_MANIFEST_PATH}",
                json=_manifest_payload(),
                timeout=5,
            )
            self.assertEqual(response.status_code, 403)
        finally:
            server.shutdown()
            server.server_close()

    def test_self_add_and_session_retry(self) -> None:
        ok, message = self.a.engine.add_manual_peer(f"127.0.0.1:{self.a.port}")
        self.assertFalse(ok)
        self.assertIn("本机", message)

        # 首个 exchange 被 409 顶掉后，客户端应重新走清单并成功同步
        real_port = self.b.port
        state = {"expired_once": False}

        from http.server import BaseHTTPRequestHandler

        class _FlakyHandler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):  # noqa: D102
                pass

            def do_GET(self):  # noqa: N802, D102 - ping 直接转发
                forwarded = requests.get(f"http://127.0.0.1:{real_port}{self.path}", timeout=10)
                self.send_response(forwarded.status_code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(forwarded.content)))
                self.end_headers()
                self.wfile.write(forwarded.content)

            def do_POST(self):  # noqa: N802, D102
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length)
                if self.path == "/api/freenote/exchange" and not state["expired_once"]:
                    state["expired_once"] = True
                    payload = json.dumps({"error": "同步会话已过期"}).encode("utf-8")
                    self.send_response(409)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                    return
                forwarded = requests.post(
                    f"http://127.0.0.1:{real_port}{self.path}",
                    data=body,
                    headers={
                        k: v
                        for k, v in self.headers.items()
                        if k.lower() not in ("host", "content-length", "connection")
                    },
                    timeout=10,
                )
                self.send_response(forwarded.status_code)
                for name in (
                    "X-FreeNote-Sig",
                    "X-FreeNote-Ts",
                    "X-FreeNote-Nonce",
                    protocol.ENVELOPE_HEADER,
                    "Content-Type",
                ):
                    if name in forwarded.headers:
                        self.send_header(name, forwarded.headers[name])
                self.send_header("Content-Length", str(len(forwarded.content)))
                self.end_headers()
                self.wfile.write(forwarded.content)

        stub, thread = _serve(_FlakyHandler)
        try:
            self.a.store.save(Note(title="会话重试"))
            for peer in self.a.engine.peers():  # 移除直连 B 的条目，强制走代理
                self.a.engine.forget_peer(peer.id)
            ok, message = self.a.engine.add_manual_peer(f"127.0.0.1:{stub.server_address[1]}")
            self.assertTrue(ok, message)
            result = self.a.engine.sync_all()
            self.assertTrue(result.ok, result.message)
            self.assertEqual(result.pushed, 1)
            self.assertTrue(state["expired_once"])
            self.assertEqual(len(self.b.store.list_active()), 1)
        finally:
            stub.shutdown()
            stub.server_close()
            thread.join(timeout=5)


class AccountIsolationTest(unittest.TestCase):
    """同步账户：同一局域网内不同账户（服务器账号/离线账户）互不可见、不可同步。"""

    def setUp(self) -> None:
        root = Path(_TMP.name) / f"accounts_{time.time_ns()}"
        root.mkdir(parents=True, exist_ok=True)
        self.a = _Node(root, "acc-a")
        self.b = _Node(root, "acc-b")
        self.a.settings.p2p_account = "alice"
        self.b.settings.p2p_account = "bob"
        self.a.start()
        self.b.start()

    def tearDown(self) -> None:
        self.a.close()
        self.b.close()

    def test_account_isolation_and_stamping(self) -> None:
        # 手动添加被拒（账户不符）
        ok, message = self.a.engine.add_manual_peer(self.b.address())
        self.assertFalse(ok)
        self.assertIn("账户", message)

        # ping 回报账户指纹
        payload = requests.get(f"http://127.0.0.1:{self.b.port}/api/freenote/ping", timeout=6).json()
        self.assertEqual(payload.get("protocol"), config.PROTOCOL_VERSION)
        self.assertEqual(payload.get("account"), self.b.engine.account_fingerprint())
        self.assertNotEqual(payload.get("account"), self.a.engine.account_fingerprint())

        # 跨账户请求被拒，且不会写入数据
        note = self.a.store.save(Note(title="不应同步过去"))
        client = PeerClient(
            self.a.store,
            self.a.engine.group_key,
            "dev-a",
            "dev-a",
            account_fingerprint=self.a.engine.account_fingerprint(),
        )
        with self.assertRaises(PeerAccountError) as ctx:
            client.sync("127.0.0.1", self.b.port)
        self.assertIn("账户", str(ctx.exception))
        self.assertIsNone(self.b.store.get(note.id))

        # 相同账户：设备条目带账户指纹，正常同步
        self.b.settings.p2p_account = "alice"
        self.b.engine.account_changed()
        ok, message = self.a.engine.add_manual_peer(self.b.address())
        self.assertTrue(ok, message)
        self.assertEqual(self.a.engine.peers()[0].account, self.a.engine.account_fingerprint())
        summary = self.a.engine.sync_all()
        self.assertTrue(summary.ok, summary.message)
        self.assertIsNotNone(self.b.store.get(note.id))
        self.assertEqual(self.a.store.state_digest(), self.b.store.state_digest())

    def test_account_switch_and_server_precedence(self) -> None:
        self.b.settings.p2p_account = "alice"
        self.b.engine.account_changed()
        ok, message = self.a.engine.add_manual_peer(self.b.address())
        self.assertTrue(ok, message)
        self.assertEqual(len(self.a.engine.peers()), 1)

        # A 切换到新账户：旧账户的设备条目清理，本机数据不受影响
        self.a.settings.p2p_account = "carol"
        self.a.engine.account_changed()
        self.assertEqual(self.a.engine.peers(), [])
        self.assertNotEqual(
            self.a.engine.account_fingerprint(), self.b.engine.account_fingerprint()
        )

        # 服务器账号优先于离线账户
        self.a.settings.p2p_account = "some-local"
        self.a.settings.server_url = "http://192.168.1.8:8000/"
        self.a.settings.server_username = "alice"
        self.assertEqual(
            self.a.engine.account_namespace(), "server:http://192.168.1.8:8000#alice"
        )
        self.assertEqual(
            self.a.engine.account_fingerprint(),
            protocol.account_fingerprint("server:http://192.168.1.8:8000#alice"),
        )


class ProtocolUnitTest(unittest.TestCase):
    def test_account_namespace_fingerprint_and_keys(self) -> None:
        self.assertEqual(protocol.account_namespace("", "", ""), "")
        self.assertEqual(protocol.account_namespace("", "", " 家庭 "), "local:家庭")
        self.assertEqual(
            protocol.account_namespace("HTTP://Host:8000/", " alice ", "家庭"),
            "server:http://host:8000#alice",
        )
        self.assertEqual(protocol.account_namespace("http://h:1", "", "家庭"), "local:家庭")
        self.assertEqual(protocol.account_namespace("", "alice", "家庭"), "local:家庭")

        self.assertEqual(protocol.account_fingerprint(""), "")
        self.assertEqual(len(protocol.account_fingerprint("local:alice")), 16)
        self.assertNotEqual(
            protocol.account_fingerprint("local:alice"),
            protocol.account_fingerprint("local:bob"),
        )
        self.assertEqual(
            protocol.account_fingerprint("server:http://192.168.1.8:8000#alice"),
            "c5b4dae96b4bcfb2",
        )

        key_alice = protocol.derive_group_key(PASSPHRASE, "local:alice")
        key_bob = protocol.derive_group_key(PASSPHRASE, "local:bob")
        self.assertNotEqual(key_alice, key_bob)
        self.assertNotEqual(protocol.group_tag(key_alice), protocol.group_tag(key_bob))
        self.assertNotEqual(key_alice, protocol.derive_group_key(PASSPHRASE))
        self.assertNotEqual(
            protocol.group_tag(protocol.derive_group_key("AAA")),
            protocol.group_tag(protocol.derive_group_key("BBB")),
        )
        self.assertEqual(len(protocol.group_tag(key_alice)), 16)

        # 内容哈希：与 Android 端共享的向量（同毫秒平局仲裁的确定性依据）
        note = Note(
            id="note-1", title="标题", content="内容", color=0xFFBBDEFB,
            created_at=1000, updated_at=2000, is_pinned=True, tags="a,b",
            reminder_time=None, is_deleted=False, deleted_at=None,
            is_locked=True, is_checklist=True,
        )
        self.assertEqual(note.note_content_hash(), "b2dca66e3a61816f")
        # 不含 id/updatedAt：同一内容不同 id/时间戳的哈希一致
        same = Note(**{**note.__dict__, "id": "other", "updated_at": 9999})
        self.assertEqual(same.note_content_hash(), note.note_content_hash())
        changed = Note(**{**note.__dict__, "title": "改"})
        self.assertNotEqual(changed.note_content_hash(), note.note_content_hash())
        # folder 参与哈希（协议 v5），与 Android 端共享的向量
        folder_hash = content_hash_fields(
            title="t", content="c", tags="", folder="工作/项目A", color=1,
            created_at=2, is_pinned=False, is_locked=False, is_checklist=False,
            reminder_time=None, deleted_at=None,
        )
        self.assertEqual(folder_hash, "4ac050ca11394ca9")
        self.assertNotEqual(
            folder_hash,
            content_hash_fields(
                title="t", content="c", tags="", folder="", color=1,
                created_at=2, is_pinned=False, is_locked=False, is_checklist=False,
                reminder_time=None, deleted_at=None,
            ),
        )
        # 墓碑哈希只取决于 deletedAt（服务端按同一规则构造）
        self.assertEqual(tombstone_note("gone", 42).note_content_hash(), "f4095cceff9ebc49")
        self.assertEqual(
            tombstone_note("another", 42).note_content_hash(),
            tombstone_note("gone", 42).note_content_hash(),
        )

        # 与 Android 端 SyncProtocolTest 共享的已知向量（字节级互通，协议 v4 / 600k 迭代）
        self.assertEqual(
            protocol.derive_group_key("TEST-PASS-1234", "local:alice").hex(),
            "7d99c5975817e415a9ab1aef54f9221e6bb137f9feac87f0100692359c2bbc2d",
        )
        self.assertEqual(
            protocol.derive_group_key("TEST-PASS-1234").hex(),
            "0cd0385289a6121dc61eb61f43a0b9306fb8ac202d26af0a1e45753c7cf7010d",
        )

    def test_lww_manifest_signature_and_envelope(self) -> None:
        # LWW 平局规则（协议 v5）：新者胜 → 同刻删除优先 → 同刻同态按内容哈希小者胜
        self.assertTrue(protocol.incoming_wins(None, (1, False, "aa")))
        self.assertFalse(protocol.incoming_wins((2, False, "aa"), (1, False, "aa")))
        self.assertTrue(protocol.incoming_wins((1, False, "aa"), (2, False, "bb")))
        self.assertTrue(protocol.incoming_wins((1, False, "aa"), (1, True, "zz")))  # 同刻删除优先
        self.assertFalse(protocol.incoming_wins((1, True, "zz"), (1, False, "aa")))
        # 同刻同态（含双方都已删除）：哈希小者胜
        self.assertTrue(protocol.incoming_wins((1, True, "zz"), (1, True, "aa")))
        self.assertFalse(protocol.incoming_wins((1, True, "aa"), (1, True, "zz")))
        # 同刻同态：哈希小者胜，与到达顺序无关
        self.assertTrue(protocol.incoming_wins((1, False, "bb"), (1, False, "aa")))
        self.assertFalse(protocol.incoming_wins((1, False, "aa"), (1, False, "bb")))
        self.assertFalse(protocol.incoming_wins((1, False, "aa"), (1, False, "aa")))  # 内容一致
        # 哈希缺失（旧清单）：保守保留本机版本
        self.assertFalse(protocol.incoming_wins((1, False, "aa"), (1, False, "")))
        self.assertFalse(protocol.incoming_wins((1, False, ""), (1, False, "aa")))

        manifest = protocol.manifest_from_entries(
            [
                {"id": "a", "updatedAt": 5, "isDeleted": False},
                {"id": "b", "updatedAt": "6", "isDeleted": True, "hash": "abc"},
                {"id": "", "updatedAt": 7},
                "junk",
            ]
        )
        self.assertEqual(
            manifest, {"a": (5, False, ""), "b": (6, True, "abc")}
        )
        self.assertEqual(protocol.manifest_from_entries(None), {})

        key = protocol.derive_group_key(PASSPHRASE)
        body = b'{"a": 1}'
        timestamp = int(time.time() * 1000)
        nonce = protocol.new_nonce()
        signature = protocol.sign(key, "POST", "/x", timestamp, nonce, body)
        self.assertTrue(protocol.verify_signature(key, "POST", "/x", timestamp, nonce, body, signature))
        self.assertFalse(protocol.verify_signature(key, "POST", "/y", timestamp, nonce, body, signature))
        self.assertFalse(protocol.verify_signature(key, "POST", "/x", timestamp, nonce, b"{}", signature))

        # 与 Android 端共享的 AES-GCM 向量（字节级互通保证）
        android_key = protocol.derive_group_key("TEST-PASS-1234")
        blob = protocol.seal_with_nonce(android_key, bytes(range(12)), b'{"hello":"world"}')
        self.assertEqual(
            blob.hex(),
            "000102030405060708090a0b"
            "9e484eed60e0e9214a63d00fd77fc3609dc3b079c5b584ac81a0614ce76bac60b0",
        )
        self.assertEqual(protocol.unseal(android_key, blob), b'{"hello":"world"}')

        # 随机 nonce / 篡改 / 密钥不符 / 数据过短
        plain = '{"title": "绝密", "n": 1}'.encode("utf-8")
        sealed = protocol.seal(key, plain)
        self.assertNotIn("绝密".encode("utf-8"), sealed)
        self.assertNotEqual(sealed[:12], protocol.seal(key, plain)[:12])
        self.assertEqual(protocol.unseal(key, sealed), plain)
        self.assertIsNone(protocol.unseal(protocol.derive_group_key("OTHER"), sealed))
        self.assertIsNone(protocol.unseal(key, sealed[:-1] + bytes([sealed[-1] ^ 1])))
        self.assertIsNone(protocol.unseal(key, b"short"))

        cache = protocol.NonceCache()
        self.assertTrue(cache.accept("n1", now=1000))
        self.assertFalse(cache.accept("n1", now=1000))
        self.assertTrue(cache.accept("n2", now=1000))

    def test_parse_address_and_announcement(self) -> None:
        self.assertEqual(parse_address("192.168.1.8:8765"), ("192.168.1.8", 8765))
        self.assertEqual(parse_address("192.168.1.8"), ("192.168.1.8", 8765))
        self.assertEqual(parse_address("my-pc:9000"), ("my-pc", 9000))
        self.assertIsNone(parse_address(""))
        self.assertIsNone(parse_address(":"))

        announcement = Announcement(device_id="dev-1", name="笔记本", port=8765, digest="abc")
        wire = announcement.to_wire("tag-1")
        self.assertEqual(Announcement.tag_of(wire), "tag-1")
        self.assertEqual(Announcement.version_of(wire), config.PROTOCOL_VERSION)
        parsed = Announcement.from_wire(wire)
        self.assertIsNotNone(parsed)
        self.assertEqual(
            (parsed.device_id, parsed.name, parsed.port, parsed.digest),
            ("dev-1", "笔记本", 8765, "abc"),
        )
        self.assertIsNone(Announcement.from_wire(b"not json"))


class EngineLifecycleTest(unittest.TestCase):
    def test_offline_defaults_and_port_fallback(self) -> None:
        root = Path(_TMP.name) / f"lifecycle_{time.time_ns()}"
        root.mkdir(parents=True, exist_ok=True)

        # 全新安装：不监听端口、不参与发现；仅设置口令也不行，需显式开启总开关
        store = NoteStore(root / "node.db")
        settings = Settings(root / "node.json")
        try:
            self.assertFalse(
                any(
                    (
                        settings.discovery_enabled,
                        settings.auto_sync,
                        settings.server_auto_sync,
                        settings.p2p_enabled,
                    )
                )
            )
            engine = PeerSyncEngine(store, settings)
            try:
                self.assertTrue(engine.start())
                self.assertFalse(engine.running)
                self.assertFalse(engine.discovery_running)
                settings.group_passphrase = PASSPHRASE
                self.assertTrue(engine.start())
                self.assertFalse(engine.running)  # 总开关默认关：仍不监听端口
                settings.p2p_enabled = True
                self.assertTrue(engine.start())
                self.assertTrue(engine.running)
                self.assertGreater(engine.port, 0)
                # 清空口令：重启后不再监听端口
                settings.group_passphrase = ""
                self.assertTrue(engine.restart())
                self.assertFalse(engine.running)
            finally:
                engine.stop()
        finally:
            store.close()

        # 未设置口令时同步给出明确提示
        store = NoteStore(root / "stopped.db")
        settings = Settings(root / "stopped.json")
        settings.discovery_enabled = False
        try:
            summary = PeerSyncEngine(store, settings).sync_all()
            self.assertFalse(summary.ok)
            self.assertIn("口令", summary.message)
        finally:
            store.close()

        # 配置端口被占用时自动顺延，两个实例不会抢同一端口
        port = _free_tcp_port()
        nodes = []
        try:
            for index in range(2):
                store = NoteStore(root / f"port{index}.db")
                settings = Settings(root / f"port{index}.json")
                settings.group_passphrase = PASSPHRASE
                settings.p2p_enabled = True
                settings.discovery_enabled = False
                settings.auto_sync = False
                settings.sync_port = port
                engine = PeerSyncEngine(store, settings)
                self.assertTrue(engine.start())
                nodes.append((engine, store))
            first, second = nodes[0][0].port, nodes[1][0].port
            self.assertNotEqual(first, second)
            self.assertIn(port, (first, second))
        finally:
            for engine, store in nodes:
                engine.stop()
                store.close()


class DiscoveryTest(unittest.TestCase):
    def test_unicast_discovery_and_tag_filter(self) -> None:
        def pair(tag_a: str, tag_b: str, on_peer) -> tuple[DiscoveryService, DiscoveryService]:
            service_b = DiscoveryService(
                announce_provider=lambda: Announcement("dev-b", "B", 1234, "d-b"),
                tag_provider=lambda: tag_b,
                on_peer=on_peer,
                port=_free_udp_port(),
            )
            service_b.set_self_id("dev-b")
            service_a = DiscoveryService(
                announce_provider=lambda: Announcement("dev-a", "A", 4321, "d-a"),
                tag_provider=lambda: tag_a,
                on_peer=lambda *_: None,
                port=_free_udp_port(),
            )
            service_a.set_self_id("dev-a")
            self.assertTrue(service_b.start())
            self.assertTrue(service_a.start())
            return service_a, service_b

        # 相同 tag：单播公告互相发现
        received: list[tuple[Announcement, str, int]] = []
        event = threading.Event()
        service_a, service_b = pair("shared-tag", "shared-tag", lambda a, addr, p: (received.append((a, addr, p)), event.set()))
        try:
            service_a.send_unicast("127.0.0.1", service_b._port)  # noqa: SLF001
            self.assertTrue(event.wait(5), "未收到设备公告")
            announcement, address, source_port = received[0]
            self.assertEqual(announcement.device_id, "dev-a")
            self.assertEqual(announcement.name, "A")
            self.assertEqual(address, "127.0.0.1")
            self.assertEqual(source_port, service_a._port)  # noqa: SLF001
        finally:
            service_a.stop()
            service_b.stop()

        # 不同 tag：不应互相发现
        ignored = threading.Event()
        service_a, service_b = pair("expected-tag", "other-tag", lambda *_: ignored.set())
        try:
            service_a.send_unicast("127.0.0.1", service_b._port)  # noqa: SLF001
            self.assertFalse(ignored.wait(0.6), "口令不同的设备不应互相发现")
        finally:
            service_a.stop()
            service_b.stop()


class ClientUnitTest(unittest.TestCase):
    def test_ping_rejects_unreachable_peer(self) -> None:
        # 指向一个不存在的端口：应报连接失败而不是崩溃
        root = Path(_TMP.name) / f"client_{time.time_ns()}"
        root.mkdir(parents=True, exist_ok=True)
        store = NoteStore(root / "store.db")
        try:
            from freenote.p2p.client import PeerSyncError

            client = PeerClient(store, lambda: b"k", "dev", "dev")
            with self.assertRaises(PeerSyncError):
                client.ping("127.0.0.1", _free_tcp_port())
        finally:
            store.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
