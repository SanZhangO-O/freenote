"""FreeNote 核心测试：加密/PIN、清单、模型与内联图片、存储、凭据保护、备份。"""

from __future__ import annotations

import base64
import json
import os
import sys
import tempfile
import unittest
import unittest.mock
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

_TMP = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
os.environ["FREENOTE_DATA_DIR"] = _TMP.name

from freenote import backup, crypto  # noqa: E402
from freenote.database import NoteStore, escape_like  # noqa: E402
from freenote.models import (  # noqa: E402
    ChecklistItem,
    Note,
    folder_ancestors,
    folder_in_subtree,
    folder_parent,
    format_note_time,
    image_token,
    image_token_path,
    local_image_path,
    normalize_folder,
    note_from_dict,
    note_to_dict,
    parse_checklist,
    parse_image_tokens,
    serialize_checklist,
    strip_image_tokens,
    tombstone_note,
)
from freenote.settings import Settings, _protect_for_disk, try_unprotect_secret  # noqa: E402


class CryptoTest(unittest.TestCase):
    def test_note_crypto_and_pin(self) -> None:
        pin = "1234"
        for text in ["hello", "中文内容 with markdown # 标题", "multi\nline\n文本", "中文 ✓ 🎉 emoji"]:
            cipher = crypto.encrypt(text, pin)
            self.assertNotIn(text, cipher)
            self.assertEqual(crypto.decrypt(cipher, pin), text)
        # 空串原样透传（与 Android 版一致）
        self.assertEqual(crypto.encrypt("", pin), "")
        self.assertEqual(crypto.decrypt("", pin), "")
        # 错误 PIN / 损坏密文一律返回 None
        self.assertIsNone(crypto.decrypt(crypto.encrypt("secret", pin), "9999"))
        # 每次加密使用随机 IV
        self.assertNotEqual(crypto.encrypt("same", pin), crypto.encrypt("same", pin))
        # 长内容
        long_text = "".join(f"第{i}行内容，" for i in range(1, 501))
        self.assertEqual(crypto.decrypt(crypto.encrypt(long_text, "5678"), "5678"), long_text)
        # PIN 哈希与可提交校验
        stored = crypto.hash_pin("1234")
        self.assertTrue(stored.startswith("600000:"))
        self.assertTrue(crypto.verify_pin("1234", stored))
        self.assertFalse(crypto.verify_pin("1235", stored))
        self.assertFalse(crypto.verify_pin("1234", None))
        self.assertTrue(crypto.is_complete_pin("1234"))
        self.assertTrue(crypto.is_complete_pin("123456789012"))
        self.assertFalse(crypto.is_complete_pin("123"))
        self.assertFalse(crypto.is_complete_pin("1234567890123"))
        self.assertFalse(crypto.is_complete_pin("12a4"))

    def test_note_crypto_v2_format_and_legacy_vector(self) -> None:
        # v2：密文自带版本、迭代数与随机盐（同明文两次加密不同）
        cipher = crypto.encrypt("v2 格式内容", "1234")
        self.assertTrue(cipher.startswith("v2:600000:"))
        self.assertNotEqual(cipher, crypto.encrypt("v2 格式内容", "1234"))
        # 跨端固定向量（由旧版参数生成的 v1 密文与新版 v2 密文，Android CryptoUtils 同参数）
        legacy_vector = (
            "AAECAwQFBgcICQoLtIH1Ta4QTHljJ3yYMc36WjNOoJq8fvKK8lCJ4Z7G9MXlU74ZHg=="
        )
        self.assertEqual(crypto.decrypt(legacy_vector, "1234"), "旧格式密文内容")
        v2_vector = (
            "v2:600000:AAECAwQFBgcICQoLDA0ODwABAgMEBQYHCAkKC7id7RckXYRzVC35nH5MJSCG"
            "WQpI8FvASnq8P/HAAwvZUB2f53Sq"
        )
        self.assertEqual(crypto.decrypt(v2_vector, "1234"), "跨端向量 v2 内容")
        # 畸形 v2：非法迭代数/不可解码载荷一律拒绝，不因巨量迭代数空转 CPU
        self.assertIsNone(crypto.decrypt("v2:999999999999:" + "A" * 64, "1234"))
        self.assertIsNone(crypto.decrypt("v2:600000:@@@", "1234"))


class ChecklistTest(unittest.TestCase):
    def test_parse_and_serialize(self) -> None:
        items = parse_checklist("- [ ] 买牛奶\n- [x] 写代码\n- [X] 大写完成\n\n普通文本")
        self.assertEqual(
            items,
            [
                ChecklistItem(False, "买牛奶"),
                ChecklistItem(True, "写代码"),
                ChecklistItem(True, "大写完成"),
                ChecklistItem(False, "普通文本"),
            ],
        )
        self.assertEqual(parse_checklist(""), [ChecklistItem()])
        text = serialize_checklist(items)
        self.assertEqual(parse_checklist(text), items)

        # 内联图片标记在清单转换中原样保留（切换清单模式不丢图）
        marker = image_token(str(Path(_TMP.name) / "note_images" / "img.png"))
        roundtrip = parse_checklist(f"待办\n{marker}")
        self.assertEqual(serialize_checklist(roundtrip), f"- [ ] 待办\n{marker}")


class ModelsTest(unittest.TestCase):
    def test_folder_paths(self) -> None:
        self.assertEqual(normalize_folder("  工作 \\ 项目A // 子 "), "工作/项目A/子")
        self.assertEqual(normalize_folder("///"), "")
        self.assertEqual(folder_parent("工作/项目A"), "工作")
        self.assertEqual(folder_parent("工作"), "")
        self.assertEqual(folder_parent(""), "")
        self.assertEqual(folder_ancestors("a/b/c"), ["a", "a/b", "a/b/c"])
        self.assertEqual(folder_ancestors(""), [])
        self.assertTrue(folder_in_subtree("工作/项目A", "工作"))
        self.assertTrue(folder_in_subtree("工作", "工作"))
        self.assertFalse(folder_in_subtree("工作2", "工作"))
        self.assertTrue(folder_in_subtree("", ""))
        self.assertFalse(folder_in_subtree("工作", ""))

        note = Note(title="x", folder="工作/项目A")
        data = note_to_dict(note)
        self.assertEqual(data["folder"], "工作/项目A")
        self.assertEqual(note_from_dict(data).folder, "工作/项目A")
        self.assertEqual(note_from_dict({"id": "n"}).folder, "")

    def test_wire_tombstone_and_time_format(self) -> None:
        note = Note(title="标题", content="正文", tags="a,b", is_locked=True, reminder_time=123456789)
        data = note_to_dict(note)
        self.assertEqual(data["createdAt"], note.created_at)
        self.assertTrue(data["isLocked"])
        restored = note_from_dict(data)
        self.assertEqual(restored.id, note.id)
        self.assertEqual(restored.title, note.title)
        self.assertEqual(restored.reminder_time, note.reminder_time)

        tomb = tombstone_note("abc", 1234)
        self.assertTrue(tomb.is_deleted)
        self.assertEqual((tomb.updated_at, tomb.deleted_at), (1234, 1234))

        now = datetime(2026, 9, 10, 12, 0)
        same_year = int(datetime(2026, 1, 2, 3, 4).timestamp() * 1000)
        other_year = int(datetime(2025, 1, 2, 3, 4).timestamp() * 1000)
        self.assertEqual(format_note_time(same_year, now=now), "01/02 03:04")
        self.assertEqual(format_note_time(other_year, now=now), "2025/01/02 03:04")

    def test_inline_image_tokens(self) -> None:
        path = str(Path(_TMP.name) / "note_images" / "a.png")
        token = image_token(path)
        self.assertEqual(image_token_path(token), Path(path).as_uri())
        self.assertEqual(local_image_path(Path(path).as_uri()), path)

        one = image_token(str(Path(_TMP.name) / "one.png"))
        two = image_token(str(Path(_TMP.name) / "two.png"))
        content = f"第一行\n{one}\n{two}\n第二行"
        self.assertEqual(len(parse_image_tokens(content)), 2)
        self.assertEqual(strip_image_tokens(content, "[图片]"), "第一行\n[图片]\n第二行")
        self.assertEqual(strip_image_tokens(content), "第一行\n\n第二行")
        # 不是独占一行的图片标记不算图片
        self.assertEqual(parse_image_tokens(f"文字 {one}"), [])
        self.assertIsNone(image_token_path("普通文本"))


class StoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.path = Path(_TMP.name) / f"test_{self._testMethodName}.db"
        self.store = NoteStore(self.path)

    def tearDown(self) -> None:
        self.store.close()

    def test_crud_search_tags_and_reminders(self) -> None:
        a = self.store.save(Note(title="A"))
        b = self.store.save(Note(title="B", tags="工作,重要"))
        self.store.set_pinned(b.id, True)
        self.assertEqual([n.id for n in self.store.list_active()], [b.id, a.id])
        self.assertEqual([n.title for n in self.store.notes_by_tag("重要")], ["B"])
        self.assertEqual(self.store.all_tags(), ["工作", "重要"])

        # LIKE 通配符转义
        self.store.save(Note(title="100% 完成"))
        self.assertEqual(len(self.store.search("100%")), 1)
        self.assertEqual(escape_like("a_b"), "a\\_b")

        # 提醒只返回到点且未删除的
        reminder = Note(title="提醒", reminder_time=1000)
        self.store.save(reminder)
        self.assertEqual(len(self.store.due_reminders(now=2000)), 1)
        self.assertEqual(len(self.store.due_reminders(now=500)), 0)

    def test_folder_queries_rename_and_delete(self) -> None:
        root = self.store.save(Note(title="根", folder="工作"))
        child = self.store.save(Note(title="子", folder="工作/项目A"))
        other = self.store.save(Note(title="其他", folder="生活"))
        unfiled = self.store.save(Note(title="未分类"))
        self.assertEqual(self.store.all_folders(), ["工作", "工作/项目A", "生活"])
        self.assertEqual(
            sorted(n.title for n in self.store.notes_in_folder("工作")), ["子", "根"]
        )
        self.assertEqual([n.title for n in self.store.notes_in_folder("")], ["未分类"])
        self.assertEqual(self.store.folder_note_count("工作"), 2)

        # 重命名父文件夹：子树路径前缀整体改写
        self.assertEqual(self.store.rename_folder("工作", "事务"), 2)
        self.assertEqual(self.store.get(root.id).folder, "事务")
        self.assertEqual(self.store.get(child.id).folder, "事务/项目A")
        self.assertEqual(self.store.get(other.id).folder, "生活")

        # 删除文件夹：便签上移到父级，绝不删除便签
        self.assertEqual(self.store.delete_folder("事务"), 2)
        self.assertEqual(self.store.get(root.id).folder, "")
        self.assertEqual(self.store.get(child.id).folder, "项目A")
        self.assertIsNone(self.store.get(unfiled.id).folder or None)
        self.assertEqual(len(self.store.list_active()), 4)

        # 本机空文件夹占位（仅本地元数据，不参与同步）
        self.store.set_local_folders(["工作/空", "工作/空", " "])
        self.assertEqual(self.store.local_folders(), ["工作/空"])

    def test_trash_outbox_and_purge(self) -> None:
        note = self.store.save(Note(title="A"))
        self.store.move_to_trash(note.id)
        self.assertEqual((len(self.store.list_active()), len(self.store.list_trash())), (0, 1))
        self.store.restore(note.id)
        self.assertEqual(len(self.store.list_active()), 1)

        # 硬删除（清空回收站）返回被删记录并留下墓碑队列
        self.store.move_to_trash(note.id)
        purged = self.store.empty_trash()
        self.assertEqual(len(purged), 1)
        self.assertIsNone(self.store.get(note.id))
        self.assertIn((note.id, True, unittest.mock.ANY), self.store.outbox())

        # 30 天过期清理
        note2 = self.store.save(Note(title="B"))
        self.store.move_to_trash(note2.id)
        self.store._conn.execute("UPDATE notes SET deletedAt = 1 WHERE id = ?", (note2.id,))
        self.store._conn.commit()
        self.assertEqual(len(self.store.purge_expired(1000)), 1)

        # 队列按便签去重，精确清队
        dedupe = self.store.save(Note(title="C"))
        self.store.save(dedupe)
        self.store.set_pinned(dedupe.id, True)
        entries = [e for e in self.store.outbox() if e[0] == dedupe.id]
        self.assertEqual(len(entries), 1)
        self.store.outbox_clear([(entries[0][0], entries[0][2])])
        self.assertNotIn(dedupe.id, [e[0] for e in self.store.outbox()])

    def test_sync_digest_and_lww(self) -> None:
        note = self.store.save(Note(title="A"))
        entries = self.store.sync_entries()
        self.assertEqual([(e[0], e[2]) for e in entries], [(note.id, False)])
        self.assertEqual(len(entries[0]), 4)  # (id, updatedAt, isDeleted, contentHash)
        digest_before = self.store.state_digest()

        # 摘要取决于 (id, updatedAt, isDeleted, 内容哈希)
        note.title = "A2"
        self.store.save(note)
        with self.store._lock:  # noqa: SLF001 - 测试直接拨动时间戳
            self.store._conn.execute(
                "UPDATE notes SET updatedAt = updatedAt + 5 WHERE id = ?", (note.id,)
            )
            self.store._conn.commit()
        self.assertNotEqual(self.store.state_digest(), digest_before)

        # 内容哈希参与摘要：同刻不同内容也能被发现（不依赖 updatedAt 变化）
        digest_same_ts = self.store.state_digest()
        with self.store._lock:  # noqa: SLF001 - 测试直接拨动时间戳
            self.store._conn.execute(
                "UPDATE notes SET title = title || '!' WHERE id = ?", (note.id,)
            )
            self.store._conn.commit()
        self.assertNotEqual(self.store.state_digest(), digest_same_ts)

        # 未物化墓碑参与摘要；物化后摘要不变且墓碑清空
        digest_note = self.store.state_digest()
        self.store.delete_hard(note.id)
        digest_tombstone = self.store.state_digest()
        self.assertNotEqual(digest_tombstone, digest_note)
        entry = self.store.sync_entries()[0]
        self.assertTrue(entry[2])
        self.store.materialize_tombstone(note.id, entry[1])
        self.assertEqual(self.store.state_digest(), digest_tombstone)
        self.assertEqual(self.store.tombstones(), [])

        # LWW：旧版本被拒、新版本采纳；同刻删除优先
        fresh = self.store.save(Note(title="base"))
        stale = self.store.get(fresh.id)
        stale.title = "stale"
        stale.updated_at = stale.updated_at - 1000
        self.assertFalse(self.store.apply_remote_lww(stale))
        self.assertEqual(self.store.get(fresh.id).title, "base")

        newer = self.store.get(fresh.id)
        newer.title = "fresh"
        newer.updated_at = newer.updated_at + 1000
        self.assertTrue(self.store.apply_remote_lww(newer))
        self.assertEqual(self.store.get(fresh.id).title, "fresh")

        tie = tombstone_note(fresh.id, newer.updated_at)
        self.assertTrue(self.store.apply_remote_lww(tie))
        self.assertTrue(self.store.get(fresh.id).is_deleted)

        # 同毫秒同删除态：内容哈希小者胜（确定性仲裁，与到达顺序无关）
        base_ts = 9_000_000
        low = Note(
            id="tie-hash", title="哈希较小版", created_at=base_ts, updated_at=base_ts
        )
        high = Note(
            id="tie-hash", title="哈希较大版", created_at=base_ts, updated_at=base_ts
        )
        if high.note_content_hash() < low.note_content_hash():
            low, high = high, low  # 保证 low 的哈希一定更小
        self.store.apply_remote(high)
        self.assertEqual(self.store.get("tie-hash").title, high.title)
        self.assertTrue(self.store.apply_remote_lww(low))  # 哈希更小 → 采纳
        self.assertEqual(self.store.get("tie-hash").title, low.title)
        self.assertFalse(self.store.apply_remote_lww(high))  # 哈希更大 → 拒绝
        self.assertEqual(self.store.get("tie-hash").title, low.title)


class SettingsSecretTest(unittest.TestCase):
    """同步凭据不应明文落盘（Windows 上经 DPAPI 保护），且换机解不开时不得抹掉密文。"""

    def test_secret_protection_and_persistence(self) -> None:
        protected = _protect_for_disk("MY-SECRET-CODE")  # 生产路径：save() 用它落盘
        if protected is None:  # Windows 上 DPAPI 不可用时不得回退明文
            self.fail("win32 上 DPAPI 应可用；不可用时 save() 会沿用旧密文而非明文")
        if protected != "MY-SECRET-CODE":  # DPAPI 可用（Windows）
            self.assertTrue(protected.startswith("dpapi:"))
            self.assertEqual(try_unprotect_secret(protected), (True, "MY-SECRET-CODE"))
        self.assertEqual(try_unprotect_secret("plain-value"), (True, "plain-value"))
        self.assertEqual(_protect_for_disk(""), "")

        # 设置文件中的口令、令牌、WebDAV 密码与 PIN 哈希不落明文，但可正常回读
        path = Path(_TMP.name) / "secret_settings.json"
        settings = Settings(path)
        settings.group_passphrase = "P2P-SECRET-1234"
        settings.server_token = "bearer-secret-token"
        settings.webdav_password = "dav-secret-password"
        settings.pin_hash = crypto.hash_pin("1234")
        if sys.platform == "win32":
            raw = path.read_text(encoding="utf-8")
            self.assertNotIn("P2P-SECRET-1234", raw)
            self.assertNotIn("bearer-secret-token", raw)
            self.assertNotIn("dav-secret-password", raw)
            self.assertNotIn(settings.pin_hash or "", raw)
        reloaded = Settings(path)
        self.assertEqual(reloaded.group_passphrase, "P2P-SECRET-1234")
        self.assertEqual(reloaded.server_token, "bearer-secret-token")
        self.assertEqual(reloaded.webdav_password, "dav-secret-password")
        self.assertEqual(reloaded.pin_hash, settings.pin_hash)

        # 解不开的旧密文：内存按空处理，保存时原样保留；用户重设后覆盖
        sealed = "dpapi:" + base64.b64encode(b"not-a-real-dpapi-blob").decode("ascii")
        data = json.loads(path.read_text(encoding="utf-8"))
        data["group_passphrase"] = sealed
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

        broken = Settings(path)
        self.assertEqual(broken.group_passphrase, "")
        self.assertEqual(
            json.loads(path.read_text(encoding="utf-8"))["group_passphrase"], sealed
        )
        broken.group_passphrase = "NEW-CODE"
        self.assertNotEqual(
            json.loads(path.read_text(encoding="utf-8"))["group_passphrase"], sealed
        )
        self.assertEqual(Settings(path).group_passphrase, "NEW-CODE")

    def test_scheme_less_server_url_migrates_to_legacy_http(self) -> None:
        # 升级迁移：存量无协议前缀的服务器地址按旧语义固定为 http，避免被静默切到 https
        path = Path(_TMP.name) / "url_settings.json"
        path.write_text(
            json.dumps({"server_url": "sync.example.com:8000"}), encoding="utf-8"
        )
        settings = Settings(path)
        self.assertEqual(settings.server_url, "http://sync.example.com:8000")
        self.assertEqual(
            json.loads(path.read_text(encoding="utf-8"))["server_url"],
            "http://sync.example.com:8000",
        )


class BackupTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = NoteStore(Path(_TMP.name) / "backup_src.db")
        self.target = NoteStore(Path(_TMP.name) / "backup_dst.db")
        self.file = Path(_TMP.name) / "backup.json"

    def tearDown(self) -> None:
        self.source.close()
        self.target.close()

    def test_export_import(self) -> None:
        self.source.save(Note(title="普通", tags="工作"))
        self.source.save(Note(title="Q0xJUEhFUg==", is_locked=True))
        result = backup.export_notes(self.source.list_active(), self.file)
        self.assertTrue(result.success, result.message)

        result = backup.import_notes(self.target, self.file)
        self.assertTrue(result.success, result.message)
        self.assertEqual(result.count, 2)
        imported = self.target.list_active()
        self.assertEqual(len(imported), 2)
        self.assertTrue(any(n.is_locked for n in imported))

        # 重复导入按 id 跳过
        self.assertEqual(backup.import_notes(self.target, self.file).count, 0)

    def test_import_newer_overwrites_older_and_enqueues(self) -> None:
        note = self.source.save(Note(title="备份新版本"))
        self.assertTrue(backup.export_notes(self.source.list_active(), self.file).success)
        # 本机存在同 id 的旧版本：导入应覆盖为备份内容并进入待同步队列
        self.target.apply_remote(
            Note(id=note.id, title="本机旧版本", updated_at=note.updated_at - 1000)
        )
        result = backup.import_notes(self.target, self.file)
        self.assertTrue(result.success, result.message)
        self.assertEqual(result.count, 1)
        self.assertEqual(self.target.get(note.id).title, "备份新版本")
        self.assertIn(note.id, [entry[0] for entry in self.target.outbox()])

    def test_import_keeps_newer_local_copy(self) -> None:
        note = self.source.save(Note(title="备份旧版本"))
        self.assertTrue(backup.export_notes(self.source.list_active(), self.file).success)
        self.target.apply_remote(
            Note(id=note.id, title="本机新版本", updated_at=note.updated_at + 1000)
        )
        result = backup.import_notes(self.target, self.file)
        self.assertEqual(result.count, 0)
        self.assertEqual(self.target.get(note.id).title, "本机新版本")


class SyncClientHelpersTest(unittest.TestCase):
    def test_server_url_and_https_hint(self) -> None:
        from freenote.sync_client import _needs_https_hint, normalize_server_url

        self.assertEqual(normalize_server_url("192.168.1.8:8000"), "http://192.168.1.8:8000")
        self.assertEqual(normalize_server_url("example.com:8000"), "https://example.com:8000")
        self.assertEqual(normalize_server_url("http://a.b/c/"), "http://a.b/c")
        self.assertEqual(normalize_server_url(""), "")

        self.assertFalse(_needs_https_hint("http://192.168.1.8:8000"))
        self.assertFalse(_needs_https_hint("http://10.0.0.5:8000"))
        self.assertFalse(_needs_https_hint("http://127.0.0.1:8000"))
        self.assertFalse(_needs_https_hint("https://example.com"))
        self.assertTrue(_needs_https_hint("http://example.com"))
        self.assertTrue(_needs_https_hint("http://8.8.8.8:8000"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
