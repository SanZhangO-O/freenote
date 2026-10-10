"""UI 冒烟测试（离屏运行）：主窗口/编辑器/回收站/锁定便签/设置面板。

只做构造与关键交互的快速冒烟；P2P 同步的正确性由 test_p2p 覆盖，此处不再驱动真实网络。
"""

from __future__ import annotations

import os
import sys
import tempfile
import time
import unittest
import uuid
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT))

_TMP = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
os.environ["FREENOTE_DATA_DIR"] = str(Path(_TMP.name) / "data")

from PySide6.QtWidgets import QApplication  # noqa: E402

from freenote import crypto  # noqa: E402
from freenote.database import NoteStore  # noqa: E402
from freenote.models import ChecklistItem, Note, serialize_checklist  # noqa: E402
from freenote.p2p.engine import PeerInfo, PeerSyncEngine  # noqa: E402
from freenote.session import UnlockSession  # noqa: E402
from freenote.settings import Settings  # noqa: E402
from freenote.ui.dialogs import SettingsDialog, generate_passphrase  # noqa: E402
from freenote.ui.main_window import MainWindow  # noqa: E402

_app = QApplication.instance() or QApplication([])


class UiSmokeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = NoteStore(Path(_TMP.name) / f"ui_{self._testMethodName}.db")
        self.settings = Settings(Path(_TMP.name) / f"ui_{self._testMethodName}.json")
        self.settings.discovery_enabled = False
        self.settings.auto_sync = False
        self.settings.sync_port = 0
        self.session = UnlockSession()
        self.engine = PeerSyncEngine(self.store, self.settings)
        self.window = MainWindow(self.store, self.settings, self.session, self.engine)

    def tearDown(self) -> None:
        self.window._force_quit = True  # noqa: SLF001 - 测试环境可能报告托盘可用
        self.window._search_timer.stop()  # noqa: SLF001 - 防止防抖定时器在关库后触发
        self.window.close()
        self.window.deleteLater()
        self.engine.stop()
        self.store.close()

    def _process_events_for(self, seconds: float) -> None:
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            _app.processEvents()
            time.sleep(0.01)

    def _wait_for_p2p_apply(self, timeout: float = 15.0) -> None:
        """等待后台线程应用 P2P 设置完成（重启服务是异步的）。"""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            _app.processEvents()
            if self.window._p2p_config_worker is None and not self.window._p2p_config_pending:  # noqa: SLF001
                _app.processEvents()
                if self.window._p2p_config_worker is None:  # noqa: SLF001
                    return
            time.sleep(0.01)
        self.fail("等待 P2P 设置应用超时")

    def test_editor_trash_lock_and_settings_smoke(self) -> None:
        # 新建 + 编辑 + 搜索
        self.window._new_note()
        self.window.editor.title_edit.setText("会议记录")
        self.window.editor.plain_edit.setPlainText("# 待办\n- 写周报")
        self.assertTrue(self.window.editor.save())
        self.assertEqual(self.store.list_active()[0].title, "会议记录")

        self.window._show_list()
        self.assertEqual(self.window.cards_layout.count(), 1)
        self.window.search_edit.setText("不存在的内容")
        self._process_events_for(0.4)  # 搜索输入有 200ms 防抖
        self.assertEqual(self.window.cards_layout.count(), 0)
        self.window.search_edit.setText("")

        # 清单模式
        self.window._new_note()
        editor = self.window.editor
        editor.title_edit.setText("购物")
        editor.checklist_button.setChecked(True)
        editor.checklist_editor.set_content(serialize_checklist([ChecklistItem(False, "牛奶")]))
        self.assertTrue(editor.save())
        note = next(n for n in self.store.list_active() if n.title == "购物")
        self.assertTrue(note.is_checklist)
        self.assertIn("牛奶", note.content)

        # 回收站：移入 → 恢复
        trash_id = note.id
        self.store.move_to_trash(trash_id)
        self.window._show_list()
        self.window._show_trash()
        self.assertEqual(self.window.trash_layout.count(), 1)
        self.window._restore_note(trash_id)
        self.assertEqual(len(self.store.list_active()), 2)

        # 锁定便签：保存为密文，带 PIN 重新打开可解密
        self.settings.pin_hash = crypto.hash_pin("1234")
        self.session.set_pin("1234")
        self.window._new_note()
        editor = self.window.editor
        editor.title_edit.setText("机密")
        editor.plain_edit.setPlainText("秘密内容")
        editor.lock_button.setChecked(True)
        self.assertTrue(editor.save())
        locked = next(n for n in self.store.list_active() if n.is_locked)
        self.assertNotIn("机密", locked.title)
        self.assertEqual(crypto.decrypt(locked.content, "1234"), "秘密内容")
        self.session.clear()
        self.session.set_pin("1234")
        self.window._open_note(locked.id)
        self.assertEqual(self.window.editor.title_edit.text(), "机密")
        self.assertEqual(self.window.editor.plain_edit.toPlainText(), "秘密内容")

        # 设置面板：设备列表、口令/账户/服务器字段与引擎开关
        dialog = SettingsDialog(self.window, self.settings, 0)
        try:
            self.assertIsNotNone(dialog.pin_label.text())
            dialog.set_sync_status("未同步")
            dialog.set_peers(
                [
                    PeerInfo(id="dev-1", name="书房电脑", address="192.168.1.8", port=8765, last_seen=1),
                    PeerInfo(id="manual:10.0.0.2:8765", address="10.0.0.2", port=8765, manual=True),
                ]
            )
            self.assertEqual(dialog.peers_table.rowCount(), 2)

            self.assertFalse(self.engine.running)
            self.assertFalse(dialog.p2p_enable_check.isChecked())  # 网络功能默认关闭
            dialog.passphrase_edit.setText("UI-PASS-1234")
            dialog.device_name_edit.setText("测试设备")
            dialog._apply_fields()  # noqa: SLF001 - 正常由 editingFinished 触发
            self.assertEqual(self.settings.group_passphrase, "UI-PASS-1234")
            self.assertEqual(self.settings.device_name, "测试设备")
            self.window._on_p2p_settings_changed()  # noqa: SLF001
            self._wait_for_p2p_apply()
            self.assertFalse(self.engine.running)  # 未开启总开关：不监听
            dialog.p2p_enable_check.setChecked(True)
            dialog._apply_fields()  # noqa: SLF001
            self.window._on_p2p_settings_changed()  # noqa: SLF001
            self._wait_for_p2p_apply()
            self.assertTrue(self.engine.running)
            self.assertGreater(self.engine.port, 0)

            dialog._generate_account_uuid()  # noqa: SLF001
            account = dialog.account_edit.text().strip()
            self.assertEqual(str(uuid.UUID(account)), account)
            self.assertEqual(self.engine.account_namespace(), f"local:{account}")
            dialog._copy_account()  # noqa: SLF001 - 复制到剪贴板（非空账户，无弹窗）
            self.assertEqual(_app.clipboard().text(), account)
            dialog.account_edit.setText("")
            dialog._apply_fields()  # noqa: SLF001
            self.assertEqual(self.settings.p2p_account, "")

            dialog.server_url_edit.setText("http://192.168.1.8:8000/")
            dialog.server_user_edit.setText("alice")
            dialog._apply_server_fields()  # noqa: SLF001
            self.assertEqual(self.settings.server_url, "http://192.168.1.8:8000")
            self.assertEqual(self.settings.server_username, "alice")
            self.assertFalse(self.settings.server_ready())
            dialog.set_server_busy(True)
            self.assertFalse(dialog.server_test_button.isEnabled())
            dialog.set_server_busy(False)
            self.assertTrue(dialog.server_test_button.isEnabled())
            self.settings.server_token = "token-123"
            dialog.refresh_server_state()
            self.assertIn("alice", dialog.server_status.text())
            self.assertTrue(dialog.server_logout_button.isEnabled())

            code = generate_passphrase()
            self.assertEqual((len(code.split("-")), len(code)), (4, 19))
            self.assertNotEqual(code, generate_passphrase())
            self._wait_for_p2p_apply()  # 服务器账户变更也会在后台应用
        finally:
            dialog.deleteLater()

    def test_switching_notes_flushes_unsaved_edits(self) -> None:
        window = self.window
        window._new_note()
        window.editor.title_edit.setText("第一条")
        window.editor.plain_edit.setPlainText("内容 A")
        self.assertTrue(window.editor.save())
        first_id = self.store.list_active()[0].id

        # 修改后不等待自动保存，直接切到新便签：挂起的编辑必须落库
        window.editor.plain_edit.setPlainText("内容 A 修改版")
        window._new_note()
        self.assertEqual(self.store.get(first_id).content, "内容 A 修改版")

        # 未保存的新便签草稿切走时也要落库
        window.editor.title_edit.setText("草稿")
        window.editor.plain_edit.setPlainText("草稿内容")
        self.window._open_note(first_id)
        titles = [n.title for n in self.store.list_active()]
        self.assertIn("草稿", titles)

    def test_plain_note_load_skips_checklist_build(self) -> None:
        content = "\n".join(f"第 {i} 行" for i in range(200))
        note = self.store.save(Note(title="长文", content=content))
        self.window._open_note(note.id)
        # 非清单便签不应逐行创建清单控件（只保留初始空行）
        self.assertEqual(len(self.window.editor.checklist_editor._rows), 1)  # noqa: SLF001
        self.assertEqual(self.window.editor.plain_edit.toPlainText(), content)

    def test_change_pin_worker_reencrypts_locked_notes(self) -> None:
        from freenote.ui.main_window import ChangePinWorker

        self.settings.pin_hash = crypto.hash_pin("1234")
        self.session.set_pin("1234")
        self.window._new_note()
        self.window.editor.title_edit.setText("机密")
        self.window.editor.plain_edit.setPlainText("秘密内容")
        self.window.editor.lock_button.setChecked(True)
        self.assertTrue(self.window.editor.save())
        locked = next(n for n in self.store.list_active() if n.is_locked)

        results: list[tuple[int, int, str]] = []
        worker = ChangePinWorker(self.store, "1234", "5678")
        worker.done.connect(lambda ok, failed, error: results.append((ok, failed, error)))
        worker.start()
        self.assertTrue(worker.wait(15000))
        self._process_events_for(0.2)  # done 信号是排队到主线程的
        self.assertEqual(results, [(1, 0, "")])
        note = self.store.get(locked.id)
        self.assertEqual(crypto.decrypt(note.content, "5678"), "秘密内容")

    def test_screenshot(self) -> None:
        self.window._new_note()
        self.window.editor.title_edit.setText("欢迎使用 FreeNote")
        self.window.editor.plain_edit.setPlainText("# 标题\n**加粗** 与 `代码`\n\n- [ ] 待办一\n- [x] 待办二")
        self.window.editor.save()
        self.window._new_note()
        self.window.editor.title_edit.setText("购物清单")
        self.window.editor.checklist_button.setChecked(True)
        self.window.editor.checklist_editor.set_content("- [ ] 牛奶\n- [x] 面包")
        self.window.editor.save()
        self.window._show_list()
        self.window.resize(1100, 720)
        self.window.show()
        for _ in range(5):
            _app.processEvents()
        self.window.layout().activate()
        self.window.repaint()
        _app.processEvents()
        target = Path(__file__).resolve().parent / "smoke_main_window.png"
        self.assertTrue(self.window.grab().save(str(target)))
        self.assertGreater(target.stat().st_size, 1000)


if __name__ == "__main__":
    unittest.main(verbosity=2)
