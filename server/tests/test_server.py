"""服务端 API 测试：注册/登录、推送拉取、LWW 冲突、墓碑删除、账户隔离。"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

_TMP = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
os.environ["FREENOTE_SERVER_DB"] = str(Path(_TMP.name) / "test.db")

from fastapi.testclient import TestClient  # noqa: E402

from freenote_server.app import app  # noqa: E402
from freenote_server.storage import content_hash_fields  # noqa: E402


def note(note_id: str, updated_at: int, **extra) -> dict:
    base = {
        "id": note_id,
        "title": f"标题 {note_id}",
        "content": "内容",
        "color": 0xFFFFF9C4,
        "createdAt": 1000,
        "updatedAt": updated_at,
        "isPinned": False,
        "isDeleted": False,
        "deletedAt": None,
        "tags": "工作,重要",
        "reminderTime": None,
        "isLocked": False,
        "isChecklist": False,
        "folder": "",
    }
    base.update(extra)
    return base


class ServerApiTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = TestClient(app)
        self.username = f"user{uuid.uuid4().hex[:10]}"
        response = self.client.post(
            "/api/register", json={"username": self.username, "password": "secret123"}
        )
        self.assertEqual(response.status_code, 201, response.text)
        self.token = response.json()["token"]
        self.auth = {"Authorization": f"Bearer {self.token}"}

    def sync(self, since: int, notes=None, tombstones=None) -> dict:
        response = self.client.post(
            "/api/sync",
            json={"sinceSeq": since, "notes": notes or [], "tombstones": tombstones or []},
            headers=self.auth,
        )
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def pulled(self, since: int = 0) -> dict[str, dict]:
        return {n["id"]: n for n in self.sync(since)["notes"]}

    def test_health_register_login_and_auth(self) -> None:
        response = self.client.get("/api/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ok")

        # 重复注册与错误密码
        response = self.client.post(
            "/api/register", json={"username": self.username, "password": "secret123"}
        )
        self.assertEqual(response.status_code, 409)
        response = self.client.post(
            "/api/login", json={"username": self.username, "password": "wrong-pass"}
        )
        self.assertEqual(response.status_code, 401)
        # 正确的登录凭据返回可用令牌
        response = self.client.post(
            "/api/login", json={"username": self.username, "password": "secret123"}
        )
        self.assertEqual(response.status_code, 200, response.text)
        self.assertTrue(response.json()["token"])

        # 未携带令牌无法同步
        response = self.client.post("/api/sync", json={"sinceSeq": 0})
        self.assertEqual(response.status_code, 401)

    def test_sync_push_pull_lww_tombstone_and_ciphertext(self) -> None:
        first = self.sync(0, notes=[note("n1", 1000), note("n2", 1000)])
        self.assertEqual(first["applied"], 2)
        self.assertEqual(len(first["notes"]), 2)

        # 已同步过的设备不再收到旧变更；新变更只增量下发
        self.assertEqual(self.sync(first["seq"])["notes"], [])
        third = self.sync(first["seq"], notes=[note("n3", 2000)])
        self.assertEqual([n["id"] for n in third["notes"]], ["n3"])

        # LWW：更旧的写入被拒绝，更新的写入被接受
        self.sync(0, notes=[note("n1", 1200, title="旧")])
        response = self.sync(0, notes=[note("n1", 500, title="更旧")])
        self.assertEqual(response["conflicts"], 1)
        self.assertEqual(self.pulled()["n1"]["title"], "旧")
        self.sync(0, notes=[note("n1", 1500, title="新")])
        self.assertEqual(self.pulled()["n1"]["title"], "新")

        # 墓碑传播删除
        seq = self.sync(0)["seq"]
        self.sync(seq, tombstones=[{"id": "n1", "deletedAt": 3000}])
        self.assertTrue(self.pulled()["n1"]["isDeleted"])

        # 锁定便签原样透传（服务端只存密文）
        self.sync(0, notes=[note("locked", 4000, isLocked=True, title="QUJD", content="REVG")])
        pulled = self.pulled()["locked"]
        self.assertEqual((pulled["title"], pulled["content"]), ("QUJD", "REVG"))

        # 文件夹路径原样透传（协议 v5）
        self.sync(0, notes=[note("folder-note", 5000, folder="工作/项目A")])
        self.assertEqual(self.pulled()["folder-note"]["folder"], "工作/项目A")

        # 硬删除墓碑：folder 归一为空串（与客户端 tombstone_note 的空 folder 语义一致）
        self.sync(0, notes=[note("folder-deleted", 6000, folder="工作")])
        seq = self.sync(0)["seq"]
        self.sync(seq, tombstones=[{"id": "folder-deleted", "deletedAt": 7000}])
        deleted = self.pulled()["folder-deleted"]
        self.assertTrue(deleted["isDeleted"])
        self.assertEqual(deleted["folder"], "")

    def test_same_timestamp_tie_breaks_are_deterministic(self) -> None:
        """协议 v5 同毫秒平局：删除优先；同态按内容哈希小者胜（与到达顺序无关）。"""

        def tie_note(note_id: str, title: str) -> dict:
            payload = note(note_id, 5000, title=title)
            payload["contentHash"] = content_hash_fields(
                title=title, content=payload["content"], tags=payload["tags"],
                folder=payload["folder"], color=payload["color"],
                created_at=payload["createdAt"],
                is_pinned=False, is_locked=False, is_checklist=False,
                reminder_time=None, deleted_at=None,
            )
            return payload

        # 编辑 vs 编辑（同毫秒）：哈希小者胜，先到后到结论一致
        first = tie_note("t1", "内容甲")
        second = tie_note("t1", "内容乙")
        winner_hash = min(first["contentHash"], second["contentHash"])
        winner = "内容甲" if first["contentHash"] == winner_hash else "内容乙"
        for order in ([first, second], [second, first]):
            self.sync(0, notes=order)
            self.assertEqual(self.pulled()["t1"]["title"], winner)
        # 幂等：重复推送相同内容不产生冲突
        response = self.sync(0, notes=[first if winner == "内容甲" else second])
        self.assertEqual((response["applied"], response["conflicts"]), (0, 0))

        # 编辑 vs 删除（同毫秒）：删除优先，且与到达顺序无关
        for order in ("edit_first", "delete_first"):
            self.sync(0, notes=[tie_note("t2", "任意")])
            seq = self.sync(0)["seq"]
            if order == "edit_first":
                self.sync(seq, notes=[tie_note("t2", "编辑版")])
                self.sync(0, tombstones=[{"id": "t2", "deletedAt": 5000}])
            else:
                self.sync(0, tombstones=[{"id": "t2", "deletedAt": 5000}])
                self.sync(0, notes=[tie_note("t2", "编辑版")])
            self.assertTrue(self.pulled()["t2"]["isDeleted"], order)

        # 同毫秒重复墓碑：幂等不冲突
        response = self.sync(0, tombstones=[{"id": "t2", "deletedAt": 5000}])
        self.assertEqual((response["applied"], response["conflicts"]), (0, 0))

        # 保留合法的 color=0（黑色），不被替换为默认色
        self.sync(0, notes=[note("black", 100, color=0)])
        self.assertEqual(self.pulled()["black"]["color"], 0)

    def test_malformed_integers_are_skipped_not_500(self) -> None:
        """inf/超大整数：条目被跳过并计入 conflicts，不再触发 500 或伪造时间戳。"""
        response = self.sync(
            0,
            notes=[note("huge-updated", 10**30), note("huge-color", 1000, color=10**30), note("ok", 1000)],
        )
        self.assertEqual((response["applied"], response["conflicts"]), (1, 2))
        self.assertEqual([n["id"] for n in response["notes"]], ["ok"])

        # 超大 sinceSeq 拉取被钳制，正常返回空增量
        self.assertEqual(self.sync(10**30)["notes"], [])

        # 超大 deletedAt 的墓碑被跳过
        response = self.sync(0, tombstones=[{"id": "ok", "deletedAt": 10**30}])
        self.assertEqual((response["applied"], response["conflicts"]), (0, 1))
        self.assertFalse(self.pulled()["ok"]["isDeleted"])

    def test_infinite_updated_at_is_skipped(self) -> None:
        # httpx 拒绝序列化 inf，恶意客户端发的是 Infinity 字面量，用原始 JSON 体模拟
        payload = note("inf-updated", 1000)
        payload["updatedAt"] = float("inf")
        body = json.dumps(
            {"sinceSeq": 0, "notes": [payload, note("ok", 2000)], "tombstones": []}
        )
        response = self.client.post(
            "/api/sync",
            content=body,
            headers={**self.auth, "Content-Type": "application/json"},
        )
        self.assertEqual(response.status_code, 200, response.text)
        data = response.json()
        self.assertEqual((data["applied"], data["conflicts"]), (1, 1))
        self.assertEqual([n["id"] for n in data["notes"]], ["ok"])

    def test_invalid_updated_at_and_injected_ids_are_skipped(self) -> None:
        missing = note("missing-updated", 1000)
        del missing["updatedAt"]
        invalid = note("invalid-updated", 1000)
        invalid["updatedAt"] = "abc"
        injected = note("bad:id\nmulti", 1000)
        response = self.sync(0, notes=[missing, invalid, injected, note("valid", 1000)])
        self.assertEqual((response["applied"], response["conflicts"]), (1, 3))
        self.assertEqual(sorted(self.pulled()), ["valid"])

    def test_oversized_fields_and_batches_are_rejected(self) -> None:
        response = self.sync(
            0,
            notes=[
                note("long-title", 1000, title="题" * 1001),
                note("long-content", 1000, content="a" * (512 * 1024 + 1)),
                note("many-tags", 1000, tags=",".join(f"t{i}" for i in range(51))),
                note("fits", 1000),
            ],
        )
        self.assertEqual((response["applied"], response["conflicts"]), (1, 3))
        self.assertEqual([n["id"] for n in response["notes"]], ["fits"])

        response = self.client.post(
            "/api/sync",
            json={"sinceSeq": 0, "notes": [note(f"batch{i}", 1000) for i in range(5001)]},
            headers=self.auth,
        )
        self.assertEqual(response.status_code, 422)

    def test_users_are_isolated(self) -> None:
        self.sync(0, notes=[note("secret", 1000)])
        other = self.client.post(
            "/api/register",
            json={"username": f"other{uuid.uuid4().hex[:10]}", "password": "secret123"},
        ).json()
        pulled = self.client.post(
            "/api/sync",
            json={"sinceSeq": 0, "notes": [], "tombstones": []},
            headers={"Authorization": f"Bearer {other['token']}"},
        ).json()
        self.assertEqual(pulled["notes"], [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
