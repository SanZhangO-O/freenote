"""入站同步的设备登记防护：自指路由不登记，手动地址不被覆盖（模拟器/NAT 环回场景）。"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

_TMP = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
os.environ["FREENOTE_DATA_DIR"] = str(Path(_TMP.name) / "data")

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT))

from freenote.database import NoteStore  # noqa: E402
from freenote.p2p.engine import PeerSyncEngine  # noqa: E402
from freenote.settings import Settings  # noqa: E402

_OTHER_ID = "11111111-2222-3333-4444-555555555555"
_SELF_PORT = 8765


class RemoteSyncRegistrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.engine = PeerSyncEngine(
            NoteStore(Path(_TMP.name) / f"reg_{self.id()}.db"),
            Settings(Path(_TMP.name) / f"reg_{self.id()}.json"),
        )
        self.engine.settings.sync_port = _SELF_PORT

    def tearDown(self) -> None:
        self.engine.stop()
        self.engine.store.close()

    def test_self_device_id_is_not_registered(self) -> None:
        self.engine._on_remote_sync(  # noqa: SLF001
            self.engine.settings.device_id, "自己", "127.0.0.1", _SELF_PORT, 0, 0
        )
        self.assertEqual(self.engine.peers(), [])

    def test_self_referential_route_is_not_registered(self) -> None:
        # 模拟器 NAT 环回：源地址 = 本机地址，端口 = 本机监听端口
        self.engine._on_remote_sync(_OTHER_ID, "A", "127.0.0.1", _SELF_PORT, 0, 0)  # noqa: SLF001
        self.assertEqual(self.engine.peers(), [])

    def test_normal_registration_still_works(self) -> None:
        self.engine._on_remote_sync(_OTHER_ID, "A", "10.1.2.3", _SELF_PORT, 0, 0)  # noqa: SLF001
        peers = self.engine.peers()
        self.assertEqual(len(peers), 1)
        self.assertEqual(peers[0].address, "10.1.2.3")
        self.assertEqual(peers[0].name, "A")

    def test_manual_peer_address_is_preserved(self) -> None:
        self.engine._on_remote_sync(_OTHER_ID, "A", "10.1.2.3", _SELF_PORT, 0, 0)  # noqa: SLF001
        with self.engine._lock:  # noqa: SLF001
            self.engine._peers[_OTHER_ID].manual = True  # noqa: SLF001
        # 入站登记带来自指地址：手动配置的可用路由不能被覆盖
        self.engine._on_remote_sync(_OTHER_ID, "A", "127.0.0.1", _SELF_PORT, 0, 0)  # noqa: SLF001
        self.assertEqual(self.engine.peers()[0].address, "10.1.2.3")

    def test_non_manual_peer_address_is_not_poisoned_by_self_route(self) -> None:
        self.engine._on_remote_sync(_OTHER_ID, "A", "10.1.2.3", _SELF_PORT, 0, 0)  # noqa: SLF001
        self.engine._on_remote_sync(_OTHER_ID, "A", "127.0.0.1", _SELF_PORT, 0, 0)  # noqa: SLF001
        self.assertEqual(self.engine.peers()[0].address, "10.1.2.3")
        # 正常路由变更（非自指）仍然跟随
        self.engine._on_remote_sync(_OTHER_ID, "A", "10.1.2.4", _SELF_PORT, 0, 0)  # noqa: SLF001
        self.assertEqual(self.engine.peers()[0].address, "10.1.2.4")

    def test_different_port_on_local_address_is_not_self_route(self) -> None:
        # 本机地址但端口不同：不是自指（可能是同机其他服务），允许登记
        self.engine._on_remote_sync(_OTHER_ID, "A", "127.0.0.1", _SELF_PORT + 1, 0, 0)  # noqa: SLF001
        peers = self.engine.peers()
        self.assertEqual(len(peers), 1)
        self.assertEqual(peers[0].address, "127.0.0.1")


class AnnouncementRegistrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.engine = PeerSyncEngine(
            NoteStore(Path(_TMP.name) / f"ann_{self.id()}.db"),
            Settings(Path(_TMP.name) / f"ann_{self.id()}.json"),
        )
        self.engine.settings.sync_port = _SELF_PORT
        self.engine.settings.group_passphrase = "TEST-PASSPHRASE"

    def tearDown(self) -> None:
        self.engine.stop()
        self.engine.store.close()

    def _announce(self, device_id: str, name: str, address: str, port: int, digest: str = "d1") -> None:
        from freenote.p2p.discovery import Announcement

        self.engine._on_announced(  # noqa: SLF001
            Announcement(device_id=device_id, name=name, port=port, digest=digest),
            address,
            50000,
        )

    def test_self_referential_announcement_is_not_registered(self) -> None:
        self._announce(_OTHER_ID, "A", "127.0.0.1", _SELF_PORT)
        self.assertEqual(self.engine.peers(), [])

    def test_normal_announcement_still_works(self) -> None:
        self._announce(_OTHER_ID, "A", "10.1.2.3", 8765)
        peers = self.engine.peers()
        self.assertEqual(len(peers), 1)
        self.assertEqual(peers[0].address, "10.1.2.3")
        self.assertEqual(peers[0].port, 8765)

    def test_manual_peer_address_is_preserved_against_self_route(self) -> None:
        self.engine._on_announced(_mk_announcement(_OTHER_ID, "A", 8765), "10.1.2.3", 50000)  # noqa: SLF001
        with self.engine._lock:  # noqa: SLF001
            self.engine._peers[_OTHER_ID].manual = True  # noqa: SLF001
        # 自指公告不能覆盖手动配置的可用路由
        self._announce(_OTHER_ID, "A", "192.168.0.100", _SELF_PORT)
        self.assertEqual(self.engine.peers()[0].address, "10.1.2.3")
        # 正常（非自指）公告也不能覆盖手动地址
        self._announce(_OTHER_ID, "A", "10.1.2.9", 8765)
        self.assertEqual(self.engine.peers()[0].address, "10.1.2.3")


def _mk_announcement(device_id: str, name: str, port: int):
    from freenote.p2p.discovery import Announcement

    return Announcement(device_id=device_id, name=name, port=port, digest="d1")


if __name__ == "__main__":
    unittest.main(verbosity=2)
