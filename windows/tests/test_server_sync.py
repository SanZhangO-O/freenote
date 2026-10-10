"""端到端服务器同步测试：真实 uvicorn 服务 + Windows SyncClient。

单个用例覆盖推送/拉取、LWW 冲突、删除墓碑、锁定便签密文与登录生命周期；
URL/HTTPS 辅助函数的纯逻辑断言见 test_core.py。
"""

from __future__ import annotations

import os
import socket
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

_FREENOTE_DIR = Path(__file__).resolve().parent.parent  # freenote/windows
_FREENOTE_ROOT = _FREENOTE_DIR.parent  # freenote
_SERVER_DIR = _FREENOTE_ROOT / "server"
for path in (str(_FREENOTE_DIR), str(_SERVER_DIR)):
    if path not in sys.path:
        sys.path.insert(0, path)

_TMP = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
os.environ["FREENOTE_DATA_DIR"] = str(Path(_TMP.name) / "freenote-data")
os.environ["FREENOTE_SERVER_DB"] = str(Path(_TMP.name) / "server-data" / "freenote.db")

import uvicorn  # noqa: E402

from freenote import crypto  # noqa: E402
from freenote.database import NoteStore  # noqa: E402
from freenote.models import Note, new_id  # noqa: E402
from freenote.settings import Settings  # noqa: E402
from freenote.sync_client import SyncClient  # noqa: E402
from freenote_server.app import app  # noqa: E402


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class ServerSyncE2ETest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.port = _free_port()
        cls.server = uvicorn.Server(
            uvicorn.Config(app, host="127.0.0.1", port=cls.port, log_level="warning")
        )
        cls.thread = threading.Thread(target=cls.server.run, daemon=True)
        cls.thread.start()
        deadline = time.time() + 15
        while not cls.server.started and time.time() < deadline:
            time.sleep(0.05)
        assert cls.server.started, "uvicorn 未能启动"
        cls.base_url = f"http://127.0.0.1:{cls.port}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.should_exit = True
        cls.thread.join(timeout=10)

    def test_sync_roundtrip_conflicts_delete_and_auth(self) -> None:
        suffix = str(time.time_ns())
        store_a = NoteStore(Path(_TMP.name) / f"a_{suffix}.db")
        store_b = NoteStore(Path(_TMP.name) / f"b_{suffix}.db")
        settings_a = Settings(Path(_TMP.name) / f"a_{suffix}.json")
        settings_b = Settings(Path(_TMP.name) / f"b_{suffix}.json")
        client_a = SyncClient(store_a, settings_a)
        client_b = SyncClient(store_b, settings_b)
        try:
            username = f"user{suffix[:12]}"
            result = client_a.register(self.base_url, username, "secret123")
            self.assertTrue(result.ok, result.message)
            result = client_b.login(self.base_url, username, "secret123")
            self.assertTrue(result.ok, result.message)

            # A 推送 → B 拉取；双方队列清空
            note = store_a.save(Note(title="来自A", content="hello"))
            result = client_a.sync()
            self.assertTrue(result.ok, result.message)
            self.assertEqual(result.pushed, 1)
            self.client_ok(client_b)
            self.assertEqual(store_b.get(note.id).title, "来自A")
            self.assertEqual((store_a.outbox(), store_b.outbox()), ([], []))

            # 离线编辑（LWW）：较新的一方胜出
            offline_id = new_id()
            store_a.apply_remote(Note(id=offline_id, title="base", updated_at=1_000_000))
            store_a.enqueue_sync(offline_id)
            client_a.sync()
            client_b.sync()

            a_note = store_a.get(offline_id)
            a_note.title = "A 编辑"
            a_note.updated_at = 2_000_000
            store_a.apply_remote(a_note)
            store_a.enqueue_sync(offline_id)
            b_note = store_b.get(offline_id)
            b_note.title = "B 编辑"
            b_note.updated_at = 3_000_000
            store_b.apply_remote(b_note)
            store_b.enqueue_sync(offline_id)
            self.assertEqual(client_b.sync().pushed, 1)
            result = client_a.sync()
            self.assertGreaterEqual(result.pulled, 1)
            self.assertEqual(store_a.get(offline_id).title, "B 编辑")

            # 删除传播：回收站 → 硬删除墓碑
            store_a.move_to_trash(note.id)
            client_a.sync()
            client_b.sync()
            self.assertTrue(store_b.get(note.id).is_deleted)
            store_a.empty_trash()
            client_a.sync()
            client_b.sync()
            self.assertTrue(store_b.get(note.id).is_deleted)

            # 锁定便签保持在服务端的密文形态
            cipher = crypto.encrypt("机密内容", "1234")
            locked = store_a.save(
                Note(title=crypto.encrypt("机密标题", "1234"), content=cipher, is_locked=True)
            )
            client_a.sync()
            client_b.sync()
            pulled = store_b.get(locked.id)
            self.assertTrue(pulled.is_locked)
            self.assertEqual(pulled.content, cipher)

            # 连接测试 → 退出登录 → 令牌失效清除登录态
            ok, message = client_a.test_connection(self.base_url)
            self.assertTrue(ok, message)
            client_a.logout()
            self.assertFalse(settings_a.server_token)
            self.assertFalse(client_a.sync().ok)
            settings_a.server_token = "invalid-token"
            result = client_a.sync()
            self.assertFalse(result.ok)
            self.assertIn("登录已过期", result.message)
            self.assertEqual(settings_a.server_token, "")
        finally:
            store_a.close()
            store_b.close()

    def test_server_seq_rollback_triggers_full_repull(self) -> None:
        suffix = str(time.time_ns())
        store_a = NoteStore(Path(_TMP.name) / f"r_{suffix}.db")
        store_b = NoteStore(Path(_TMP.name) / f"r2_{suffix}.db")
        settings_a = Settings(Path(_TMP.name) / f"r_{suffix}.json")
        settings_b = Settings(Path(_TMP.name) / f"r2_{suffix}.json")
        client_a = SyncClient(store_a, settings_a)
        client_b = SyncClient(store_b, settings_b)
        try:
            username = f"useq{suffix[:12]}"
            result = client_a.register(self.base_url, username, "secret123")
            self.assertTrue(result.ok, result.message)
            result = client_b.login(self.base_url, username, "secret123")
            self.assertTrue(result.ok, result.message)

            seq_key = f"last_seq:{self.base_url}:{username}"
            note_a = store_a.save(Note(title="来自A", content="hello"))
            self.assertTrue(client_a.sync().ok)
            self.assertTrue(client_b.sync().ok)
            self.assertGreater(int(store_a.meta_get(seq_key, "0") or 0), 0)

            # B 新增便签后，把 A 的 seq 调大到超过服务端计数（模拟服务端换库/恢复备份）
            note_b = store_b.save(Note(title="回退后新增", content="world"))
            client_b.sync()
            store_a.meta_set(seq_key, "999999")

            # 回退检测：本轮改从 0 全量重拉，仍能拿到 B 的新便签，且 seq 被修正
            result = client_a.sync()
            self.assertTrue(result.ok, result.message)
            self.assertEqual(store_a.get(note_b.id).title, "回退后新增")
            self.assertEqual(store_a.get(note_a.id).title, "来自A")
            self.assertLess(int(store_a.meta_get(seq_key, "0") or 0), 999999)
        finally:
            store_a.close()
            store_b.close()

    def test_401_does_not_clear_newly_logged_in_token(self) -> None:
        suffix = str(time.time_ns())
        store = NoteStore(Path(_TMP.name) / f"t_{suffix}.db")
        settings = Settings(Path(_TMP.name) / f"t_{suffix}.json")
        client = SyncClient(store, settings)
        try:
            username = f"u401{suffix[:12]}"
            result = client.register(self.base_url, username, "secret123")
            self.assertTrue(result.ok, result.message)
            original_request = client._session.request

            def swap_token_during_request(method, url, **kwargs):
                # 模拟在途请求期间用户重新登录落盘了新令牌
                settings.set("server_token", "fresh-token", save=False)
                return original_request(method, url, **kwargs)

            client._session.request = swap_token_during_request
            settings.server_token = "stale-token"
            result = client.sync()
            self.assertFalse(result.ok)
            # 401 只应作废在途的旧令牌，不能误清刚登录的新令牌
            self.assertEqual(settings.server_token, "fresh-token")
        finally:
            store.close()

    def client_ok(self, client: SyncClient) -> None:
        result = client.sync()
        self.assertTrue(result.ok, result.message)


if __name__ == "__main__":
    unittest.main(verbosity=2)
