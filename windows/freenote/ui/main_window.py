"""主窗口：便签列表/搜索/标签/排序、回收站、设置、P2P 同步与提醒调度。"""

from __future__ import annotations

import re
import threading
from datetime import datetime
from pathlib import Path

from PySide6.QtCore import QObject, Qt, QThread, QTimer, Signal
from PySide6.QtGui import QAction, QCloseEvent, QKeySequence, QShortcut
from PySide6.QtWidgets import (
    QApplication,
    QFileDialog,
    QHBoxLayout,
    QInputDialog,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMenu,
    QMessageBox,
    QScrollArea,
    QStackedWidget,
    QSystemTrayIcon,
    QToolButton,
    QTreeWidget,
    QTreeWidgetItem,
    QVBoxLayout,
    QWidget,
)

from .. import attachments, autobackup, backup, config, crypto
from ..database import NoteStore
from ..hotkey import GlobalHotkey
from ..models import (
    Note,
    attachment_name_from_path,
    folder_ancestors,
    folder_in_subtree,
    folder_parent,
    folder_sort_key,
    local_image_path,
    next_occurrence,
    normalize_folder,
    now_ms,
    parse_image_tokens,
    strip_image_tokens,
)
from ..p2p.engine import PeerSyncEngine, PeerSyncResult, SyncSummary
from ..session import UnlockSession
from ..settings import Settings
from ..sync_client import AuthResult, SyncClient, SyncResult
from . import theme as theme_mod
from .dialogs import PinDialog, SetPinDialog, SettingsDialog
from .editor_page import EditorPage
from .widgets import FlowLayout, NoteCard, clear_layout, push_button, tool_button


class EngineBridge(QObject):
    """把引擎后台线程的事件转成 Qt 信号（自动跨线程排队到主线程）。"""

    peers_changed = Signal()
    sync_result = Signal(object)
    summary = Signal(object)
    status = Signal(str)
    error = Signal(str)


class AddPeerWorker(QThread):
    """手动添加设备：先连通性检查（可能超时），放到后台线程避免卡界面。"""

    done = Signal(bool, str)

    def __init__(self, engine: PeerSyncEngine, text: str, parent: QWidget | None = None):
        super().__init__(parent)
        self.engine = engine
        self.text = text

    def run(self) -> None:  # noqa: D102
        try:
            ok, message = self.engine.add_manual_peer(self.text)
        except Exception as exc:  # noqa: BLE001
            ok, message = False, f"添加失败：{exc}"
        self.done.emit(ok, message)


class UnifiedSyncWorker(QThread):
    """一次同步任务：可选 P2P（全部设备或单台）与服务器，两者互不阻塞。"""

    done = Signal(object, object)  # (SyncSummary | PeerSyncResult | None, SyncResult | None)

    def __init__(
        self,
        engine: PeerSyncEngine,
        client: SyncClient,
        peer_id: str = "",
        include_p2p: bool = True,
        include_server: bool = True,
        parent: QWidget | None = None,
    ):
        super().__init__(parent)
        self.engine = engine
        self.client = client
        self.peer_id = peer_id
        self.include_p2p = include_p2p
        self.include_server = include_server

    def run(self) -> None:  # noqa: D102
        p2p_result: object | None = None
        server_result: SyncResult | None = None
        if self.include_p2p:
            try:
                if self.peer_id:
                    p2p_result = self.engine.sync_peer(self.peer_id)
                else:
                    p2p_result = self.engine.sync_all()
            except Exception as exc:  # noqa: BLE001
                p2p_result = SyncSummary(False, f"P2P 同步异常：{exc}", errors=[str(exc)])
        if self.include_server:
            try:
                server_result = self.client.sync()
            except Exception as exc:  # noqa: BLE001
                server_result = SyncResult(False, f"服务器同步异常：{exc}")
        self.done.emit(p2p_result, server_result)


class P2PConfigWorker(QThread):
    """应用 P2P 设置（重启服务 / 切换账户）：口令派生与全库状态摘要都很耗时，放后台执行。"""

    done = Signal(str)

    def __init__(self, engine: PeerSyncEngine, mode: str, parent: QWidget | None = None):
        super().__init__(parent)
        self.engine = engine
        self.mode = mode

    def run(self) -> None:  # noqa: D102
        try:
            if self.mode == "restart":
                if not self.engine.restart():
                    message = "无法启动 P2P 服务：端口被占用"
                elif self.engine.running:
                    message = f"P2P 服务已重启（端口 {self.engine.port}）"
                else:
                    message = "P2P 未启用：勾选「启用 P2P 同步」并填写口令后才会开启网络功能"
            else:
                self.engine.account_changed()
                message = "已切换同步账户：设备列表已按新账户重置"
        except Exception as exc:  # noqa: BLE001
            message = f"同步设置应用失败：{exc}"
        self.done.emit(message)


class ChangePinWorker(QThread):
    """修改 PIN 时用新 PIN 重新加密全部锁定便签：逐条 PBKDF2 很慢，放后台执行。"""

    done = Signal(int, int, str)  # ok, failed, error

    def __init__(self, store: NoteStore, old_pin: str, new_pin: str, parent: QWidget | None = None):
        super().__init__(parent)
        self.store = store
        self.old_pin = old_pin
        self.new_pin = new_pin

    def run(self) -> None:  # noqa: D102
        ok = failed = 0
        try:
            for note in self.store.locked_notes():
                title = crypto.decrypt(note.title, self.old_pin)
                content = crypto.decrypt(note.content, self.old_pin)
                if title is None or content is None:
                    failed += 1
                    continue
                note.title = crypto.encrypt(title, self.new_pin)
                note.content = crypto.encrypt(content, self.new_pin)
                self.store.save(note)
                ok += 1
        except Exception as exc:  # noqa: BLE001
            self.done.emit(ok, failed, str(exc))
            return
        self.done.emit(ok, failed, "")


class LockedDecryptWorker(QThread):
    """后台解密锁定便签用于列表搜索：v2 密文每条 600000 次 PBKDF2，不能放在 GUI 线程。"""

    def __init__(
        self,
        store: NoteStore,
        pin: str,
        cache: dict[str, tuple[int, str, str]],
        failed: set[tuple[str, int]],
        parent: QWidget | None = None,
    ):
        super().__init__(parent)
        self.store = store
        self.pin = pin
        self.cache = cache
        self.failed = failed

    def run(self) -> None:  # noqa: D102
        try:
            for note in self.store.list_active():
                if not note.is_locked:
                    continue
                cached = self.cache.get(note.id)
                if cached is not None and cached[0] == note.updated_at:
                    continue
                if (note.id, note.updated_at) in self.failed:
                    continue
                title = crypto.decrypt(note.title, self.pin)
                content = crypto.decrypt(note.content, self.pin)
                if title is None or content is None:
                    # 解密失败（如 PIN 已换）：记录避免每次搜索重复空转
                    self.failed.add((note.id, note.updated_at))
                    continue
                self.cache[note.id] = (note.updated_at, title, content)
        except Exception:  # noqa: BLE001
            pass


class BackupWorker(QThread):
    """导出/导入备份：大库的 JSON 读写与逐条写库放后台，避免窗口假死。"""

    done = Signal(str, bool, str)  # action, success, message

    def __init__(self, store: NoteStore, action: str, path: str, parent: QWidget | None = None):
        super().__init__(parent)
        self.store = store
        self.action = action
        self.path = path

    def run(self) -> None:  # noqa: D102
        try:
            if self.action == "export":
                result = backup.export_notes(self.store.list_active(), self.path)
            else:
                result = backup.import_notes(self.store, self.path)
        except Exception:  # noqa: BLE001
            self.done.emit(self.action, False, "备份操作失败，请重试")
            return
        self.done.emit(self.action, result.success, result.message)


class ServerAuthWorker(QThread):
    """服务器账号操作：测试连接 / 注册 / 登录 / 退出 / 改密 / 注销（阻塞网络调用放后台）。"""

    done = Signal(str, bool, str, object)  # action, ok, message, result

    def __init__(
        self,
        client: SyncClient,
        action: str,
        url: str = "",
        username: str = "",
        password: str = "",
        new_password: str = "",
        parent: QWidget | None = None,
    ):
        super().__init__(parent)
        self.client = client
        self.action = action
        self.url = url
        self.username = username
        self.password = password
        self.new_password = new_password

    def run(self) -> None:  # noqa: D102
        action = self.action
        try:
            if action == "test":
                ok, message = self.client.test_connection(self.url)
                self.done.emit(action, ok, message, None)
                return
            if action == "register":
                result: AuthResult = self.client.register(self.url, self.username, self.password)
            elif action == "login":
                result = self.client.login(self.url, self.username, self.password)
            elif action == "change_password":
                result = self.client.change_password(self.password, self.new_password)
            elif action == "delete_account":
                result = self.client.delete_account(self.password)
            elif action == "logout":
                self.client.logout()
                self.done.emit(action, True, "已退出登录", None)
                return
            else:
                self.done.emit(action, False, f"未知操作：{action}", None)
                return
            self.done.emit(action, result.ok, result.message, result)
        except Exception as exc:  # noqa: BLE001
            self.done.emit(action, False, f"服务器操作失败：{exc}", None)


class MainWindow(QMainWindow):
    images_migrated = Signal(int)  # 后台图片迁移完成（张数），转到主线程刷新
    hotkey_pressed = Signal()  # 全局热键触发（热键线程 → 主线程）
    auto_backup_done = Signal(bool, str)  # 自动备份完成（后台线程 → 主线程）

    def __init__(
        self,
        store: NoteStore,
        settings: Settings,
        session: UnlockSession,
        engine: PeerSyncEngine,
        parent: QWidget | None = None,
    ):
        super().__init__(parent)
        self.store = store
        self.settings = settings
        self._session = session
        self.engine = engine
        self.server_client = SyncClient(store, settings)

        self._theme = "light"
        self._selected_tag: str | None = None
        self._selected_folder: str | None = None
        self._all_folder_item: QTreeWidgetItem | None = None
        self._sort_mode = "updated"
        self._sort_desc = True
        self._timeline_mode = False
        self._locked_decrypt_cache: dict[str, tuple[int, str, str]] = {}
        # 解密失败记录（note_id, updated_at）：避免后台反复对同一密文做无用的 PBKDF2
        self._locked_decrypt_failed: set[tuple[str, int]] = set()
        self._locked_decrypt_worker: LockedDecryptWorker | None = None
        self._batch_mode = False
        self._batch_selected: set[str] = set()
        self._visible_note_ids: list[str] = []
        self._sync_worker: UnifiedSyncWorker | None = None
        self._add_peer_worker: AddPeerWorker | None = None
        self._server_auth_worker: ServerAuthWorker | None = None
        self._p2p_config_worker: P2PConfigWorker | None = None
        self._p2p_config_pending = ""
        self._change_pin_worker: ChangePinWorker | None = None
        self._backup_worker: BackupWorker | None = None
        self._settings_dialog: SettingsDialog | None = None
        self._last_sync_status = "未同步"
        self._last_shown_message = ""
        self._list_dirty = False
        self._force_quit = False
        self._last_notified_note_id: str | None = None

        # 搜索输入防抖：逐键全表查询 + 全量重建卡片在大库上会明显掉帧
        self._search_timer = QTimer(self)
        self._search_timer.setSingleShot(True)
        self._search_timer.setInterval(200)
        self._search_timer.timeout.connect(self._refresh_cards)
        self._tray: QSystemTrayIcon | None = None

        self._bridge = EngineBridge(self)
        self._bridge.peers_changed.connect(self._on_peers_changed)
        self._bridge.sync_result.connect(self._on_sync_result)
        self._bridge.summary.connect(self._on_summary)
        self._bridge.status.connect(self._on_engine_status)
        self._bridge.error.connect(lambda text: self.statusBar().showMessage(text, 8000))
        engine.set_event_callback(self._bridge_emit)
        self._p2p_config_snapshot = (
            settings.p2p_enabled,
            settings.sync_port,
            settings.discovery_enabled,
            settings.group_passphrase,
        )
        self._p2p_account_snapshot = (
            settings.server_url,
            settings.server_username,
            settings.p2p_account,
        )
        if engine.running:
            # 引擎在窗口创建前已启动：补一条初始状态，避免设置面板显示「未同步」
            self._last_sync_status = f"P2P 服务运行中（端口 {engine.port}）"

        self.setWindowTitle("FreeNote")
        self.setWindowIcon(theme_mod.make_app_icon())
        width = int(settings.get("window_width", 1100) or 1100)
        height = int(settings.get("window_height", 760) or 760)
        self.resize(width, height)
        saved_x = settings.get("window_x")
        saved_y = settings.get("window_y")
        if saved_x is not None and saved_y is not None:
            try:
                self.move(int(saved_x), int(saved_y))
                # 副屏被移除后保存的位置可能落在可见区域之外：回落到主屏左上角附近
                screen = QApplication.primaryScreen()
                if screen is not None and not screen.availableGeometry().intersects(
                    self.frameGeometry()
                ):
                    self.move(60, 60)
            except (TypeError, ValueError):
                pass
        if bool(settings.get("window_maximized", False)):
            self.setWindowState(self.windowState() | Qt.WindowState.WindowMaximized)

        self._build_ui()
        self._apply_theme()
        self.refresh_all()
        self._purge_trash()

        # 启动后把 legacy 的 file:// 图片引用迁移为内容寻址的便携标记（后台执行）
        self.setAcceptDrops(True)  # 支持拖拽 .md/.txt/.json 文件导入
        self.images_migrated.connect(self._after_image_migration)
        threading.Thread(
            target=self._migrate_legacy_images, name="freenote-image-migration", daemon=True
        ).start()

        # 全局热键 Ctrl+Alt+N：唤起窗口并新建便签（可在设置中关闭；注册失败会提示）
        self.hotkey_pressed.connect(self._on_hotkey)
        self._hotkey: GlobalHotkey | None = None
        self._start_hotkey()

        # 自动备份：启动数秒后检查一次，之后每 30 分钟检查是否到点
        self._auto_backup_thread: threading.Thread | None = None
        self.auto_backup_done.connect(self._on_auto_backup_done)
        self._auto_backup_timer = QTimer(self)
        self._auto_backup_timer.setInterval(30 * 60 * 1000)
        self._auto_backup_timer.timeout.connect(self._maybe_auto_backup)
        self._auto_backup_timer.start()
        QTimer.singleShot(5000, self._maybe_auto_backup)

        self.auto_timer = QTimer(self)
        self.auto_timer.timeout.connect(lambda: self._sync_now(silent=True))
        self._restart_auto_timer()

        # 启动后稍等片刻静默同步一次（主要是拉取服务器/P2P 上的新改动）。
        # 仅在用户开启了相应的自动同步时才进行：关闭自动同步 = 不做任何后台联网同步
        if self._auto_sync_enabled():
            QTimer.singleShot(4000, self, lambda: self._sync_now(silent=True))

        # 本地修改后延迟触发一次服务器同步，避免连续输入时反复请求
        self._server_change_timer = QTimer(self)
        self._server_change_timer.setSingleShot(True)
        self._server_change_timer.timeout.connect(self._server_sync_async)

        self.reminder_timer = QTimer(self)
        self.reminder_timer.setInterval(20_000)
        self.reminder_timer.timeout.connect(self._check_reminders)
        self.reminder_timer.start()

        self._setup_tray()
        self._setup_shortcuts()
        self._refresh_peer_count()

    # ------------------------------------------------------------------ 构建

    def _build_ui(self) -> None:
        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        self.stack = QStackedWidget()
        self.list_page = self._build_list_page()
        self.editor = EditorPage(self.store, self.settings, self._session)
        self.trash_page = self._build_trash_page()
        self.stack.addWidget(self.list_page)
        self.stack.addWidget(self.editor)
        self.stack.addWidget(self.trash_page)
        root.addWidget(self.stack)

        self.statusBar().showMessage("就绪")

        self.editor.back_requested.connect(self._show_list)
        self.editor.note_saved.connect(self._on_note_saved)
        self.editor.note_deleted.connect(self._on_note_deleted)
        self.editor.status_message.connect(lambda message: self.statusBar().showMessage(message, 8000))
        self.editor.wiki_link_requested.connect(self._open_wiki_link)

    def _build_list_page(self) -> QWidget:
        page = QWidget()
        root = QVBoxLayout(page)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        top = QWidget()
        top.setObjectName("TopBar")
        top_layout = QHBoxLayout(top)
        top_layout.setContentsMargins(14, 10, 14, 10)
        top_layout.setSpacing(8)

        self.search_edit = QLineEdit()
        self.search_edit.setPlaceholderText("搜索便签…")
        self.search_edit.setClearButtonEnabled(True)
        self.search_edit.setFixedWidth(300)
        self.search_edit.textChanged.connect(lambda _: self._search_timer.start())

        self.sort_button = tool_button("⇅", "排序")
        sort_menu = QMenu(self)
        self._sort_actions: dict[str, QAction] = {}
        for key, text in (("updated", "按修改时间"), ("created", "按创建时间"), ("title", "按标题")):
            action = QAction(text, self, checkable=True)
            action.triggered.connect(lambda _=False, k=key: self._set_sort(k))
            sort_menu.addAction(action)
            self._sort_actions[key] = action
        self._sort_actions["updated"].setChecked(True)
        sort_menu.addSeparator()
        self._sort_desc_action = QAction("降序（新→旧 / Z→A）", self, checkable=True)
        self._sort_desc_action.setChecked(True)
        self._sort_desc_action.triggered.connect(lambda checked: self._set_sort_direction(bool(checked)))
        sort_menu.addAction(self._sort_desc_action)
        sort_menu.addSeparator()
        self._timeline_action = QAction("按日期分组（时间线）", self, checkable=True)
        self._timeline_action.triggered.connect(lambda checked: self._set_timeline_mode(bool(checked)))
        sort_menu.addAction(self._timeline_action)
        self.sort_button.setMenu(sort_menu)
        self.sort_button.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)

        self.sync_button = tool_button("⟳", "立即同步")
        self.sync_button.clicked.connect(lambda: self._sync_now())
        self.batch_button = tool_button("☑", "批量操作（多选）", checkable=True)
        self.batch_button.toggled.connect(self._set_batch_mode)
        trash_button = tool_button("🗑", "回收站")
        trash_button.clicked.connect(self._show_trash)
        settings_button = tool_button("⚙", "设置")
        settings_button.clicked.connect(self._open_settings)
        new_button = push_button("＋ 新建便签", "Primary")
        new_button.clicked.connect(self._new_note)

        top_layout.addWidget(self.search_edit)
        top_layout.addStretch(1)
        for widget in (
            self.sort_button,
            self.sync_button,
            self.batch_button,
            trash_button,
            settings_button,
            new_button,
        ):
            top_layout.addWidget(widget)
        root.addWidget(top)

        tag_scroll = QScrollArea()
        tag_scroll.setWidgetResizable(True)
        tag_scroll.setFrameShape(QScrollArea.Shape.NoFrame)
        tag_scroll.setFixedHeight(76)
        self.tag_host = QWidget()
        self.tag_layout = FlowLayout(self.tag_host, margin=4, spacing=6)
        tag_scroll.setWidget(self.tag_host)
        root.addWidget(tag_scroll)

        # 批量操作栏（点击工具栏「☑」进入多选模式后显示）
        self.selection_bar = QWidget()
        self.selection_bar.setObjectName("TopBar")
        selection_layout = QHBoxLayout(self.selection_bar)
        selection_layout.setContentsMargins(14, 6, 14, 6)
        selection_layout.setSpacing(8)
        self.selection_label = QLabel("已选 0 条")
        self.selection_label.setObjectName("SubText")
        self.selection_all_button = push_button("全选")
        self.selection_all_button.clicked.connect(self._batch_select_all)
        self.selection_trash_button = push_button("移到回收站", "Danger")
        self.selection_trash_button.clicked.connect(self._batch_trash)
        self.selection_folder_button = push_button("移动文件夹…")
        self.selection_folder_button.clicked.connect(self._batch_move_folder)
        self.selection_tag_button = push_button("添加标签…")
        self.selection_tag_button.clicked.connect(self._batch_add_tags)
        self.selection_cancel_button = push_button("退出多选")
        self.selection_cancel_button.clicked.connect(lambda: self.batch_button.setChecked(False))
        selection_layout.addWidget(self.selection_label)
        selection_layout.addStretch(1)
        for widget in (
            self.selection_all_button,
            self.selection_folder_button,
            self.selection_tag_button,
            self.selection_trash_button,
            self.selection_cancel_button,
        ):
            selection_layout.addWidget(widget)
        self.selection_bar.setVisible(False)
        root.addWidget(self.selection_bar)

        self.cards_scroll = QScrollArea()
        self.cards_scroll.setWidgetResizable(True)
        self.cards_scroll.setFrameShape(QScrollArea.Shape.NoFrame)
        container = QWidget()
        container_layout = QVBoxLayout(container)
        container_layout.setContentsMargins(0, 0, 0, 0)
        container_layout.setSpacing(0)
        self.empty_label = QLabel("暂无便签，点击右上角「新建便签」开始记录")
        self.empty_label.setObjectName("SubText")
        self.empty_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.empty_label.setContentsMargins(0, 60, 0, 0)
        flow_host = QWidget()
        self.cards_layout = FlowLayout(flow_host, margin=16, spacing=12)
        container_layout.addWidget(self.empty_label)
        container_layout.addWidget(flow_host)
        container_layout.addStretch(1)
        self.cards_scroll.setWidget(container)

        content_row = QHBoxLayout()
        content_row.setContentsMargins(0, 0, 0, 0)
        content_row.setSpacing(0)
        content_row.addWidget(self._build_folder_sidebar())
        content_row.addWidget(self.cards_scroll, 1)
        root.addLayout(content_row, 1)
        return page

    def _build_folder_sidebar(self) -> QWidget:
        panel = QWidget()
        panel.setObjectName("FolderPanel")
        panel.setFixedWidth(212)
        layout = QVBoxLayout(panel)
        layout.setContentsMargins(8, 4, 8, 8)
        layout.setSpacing(4)

        header = QHBoxLayout()
        header.setSpacing(2)
        title = QLabel("文件夹")
        title.setObjectName("SubText")
        new_button = tool_button("＋", "新建文件夹（用 / 分隔多级）")
        rename_button = tool_button("✎", "重命名文件夹（含子文件夹）")
        delete_button = tool_button("🗑", "删除文件夹（便签移至上级，不会删除便签）")
        header.addWidget(title)
        header.addStretch(1)
        header.addWidget(new_button)
        header.addWidget(rename_button)
        header.addWidget(delete_button)
        layout.addLayout(header)

        self.folder_tree = QTreeWidget()
        self.folder_tree.setHeaderHidden(True)
        self.folder_tree.setFrameShape(QTreeWidget.Shape.NoFrame)
        self.folder_tree.setIndentation(14)
        self.folder_tree.itemSelectionChanged.connect(self._on_folder_selected)
        self.folder_tree.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.folder_tree.customContextMenuRequested.connect(self._on_folder_context_menu)
        layout.addWidget(self.folder_tree, 1)

        new_button.clicked.connect(lambda: self._create_folder())
        rename_button.clicked.connect(self._rename_selected_folder)
        delete_button.clicked.connect(self._delete_selected_folder)
        return panel

    # ------------------------------------------------------------------ 文件夹管理

    def _all_folder_paths(self) -> list[str]:
        folders: set[str] = set()
        for folder in (*self.store.all_folders(), *self.store.local_folders()):
            folders.update(folder_ancestors(folder))
        return sorted(folders, key=folder_sort_key)

    def _refresh_folders(self) -> None:
        current = self._selected_folder
        self.folder_tree.blockSignals(True)
        self.folder_tree.clear()
        self._all_folder_item = QTreeWidgetItem(["全部便签"])
        self.folder_tree.addTopLevelItem(self._all_folder_item)
        uncategorized = QTreeWidgetItem(["未分类"])
        uncategorized.setData(0, Qt.ItemDataRole.UserRole, "")
        self.folder_tree.addTopLevelItem(uncategorized)

        items: dict[str, QTreeWidgetItem] = {}
        for path in self._all_folder_paths():
            parent_path = folder_parent(path)
            item = QTreeWidgetItem([path.rsplit("/", 1)[-1]])
            item.setData(0, Qt.ItemDataRole.UserRole, path)
            item.setToolTip(0, path)
            if parent_path and parent_path in items:
                items[parent_path].addChild(item)
            else:
                self.folder_tree.addTopLevelItem(item)
            items[path] = item
        self.folder_tree.expandAll()

        if current is None:
            self.folder_tree.setCurrentItem(self._all_folder_item)
        else:
            target = items.get(current, uncategorized if current == "" else None)
            self.folder_tree.setCurrentItem(target or self._all_folder_item)
            if target is None:
                self._selected_folder = None
        self.folder_tree.blockSignals(False)

    def _selected_folder_path(self) -> str | None:
        item = self.folder_tree.currentItem()
        if item is None or item is self._all_folder_item:
            return None
        return str(item.data(0, Qt.ItemDataRole.UserRole) or "")

    def _on_folder_selected(self) -> None:
        if self._all_folder_item is None:
            return
        self._selected_folder = self._selected_folder_path()
        self._refresh_cards()

    def _on_folder_context_menu(self, point) -> None:
        item = self.folder_tree.itemAt(point)
        if item is None:
            return
        path = str(item.data(0, Qt.ItemDataRole.UserRole) or "")
        menu = QMenu(self)
        if item is self._all_folder_item or path == "":
            action = menu.addAction("新建文件夹…")
            action.triggered.connect(lambda: self._create_folder())
        else:
            create = menu.addAction("新建子文件夹…")
            create.triggered.connect(lambda: self._create_folder(path))
            rename = menu.addAction("重命名…")
            rename.triggered.connect(lambda: self._rename_selected_folder(folder=path))
            menu.addSeparator()
            delete = menu.addAction("删除文件夹")
            delete.triggered.connect(lambda: self._delete_selected_folder(folder=path))
        menu.exec(self.folder_tree.viewport().mapToGlobal(point))

    def _update_local_folders_after_rename(self, old: str, new: str) -> None:
        local: list[str] = []
        for folder in self.store.local_folders():
            if folder_in_subtree(folder, old):
                target = (new + folder[len(old):]) if new else folder[len(old):].lstrip("/")
                if target and target not in local:
                    local.append(target)
            elif folder not in local:
                local.append(folder)
        self.store.set_local_folders(local)

    def _follow_selected_folder_after_rewrite(self, old: str, new: str) -> None:
        """当前筛选位于被改写子树内时跟随移动到新路径；否则保持不变。

        右键菜单可对任意（非当前选中）文件夹重命名/删除，不能无条件篡改筛选。
        """
        current = self._selected_folder
        if current is None or not folder_in_subtree(current, old):
            return
        suffix = current[len(old):]
        self._selected_folder = (new + suffix) if new else suffix.lstrip("/")

    def _create_folder(self, parent: str = "") -> None:
        base = f"{parent}/" if parent else ""
        text, ok = QInputDialog.getText(
            self, "新建文件夹", f"文件夹{'子' if parent else ''}名称（用 / 分隔多级）：", text=base
        )
        if not ok:
            return
        folder = normalize_folder(text)
        if not folder or folder == parent:
            return
        local = self.store.local_folders()
        # 显式新建即记录本地占位：即使当前只是某条便签路径的隐式父级，
        # 也要保证子路径便签移走后该文件夹仍然存在（与 Android 端一致）
        if folder not in local:
            local.append(folder)
            self.store.set_local_folders(local)
        self._selected_folder = folder
        self.refresh_all()
        self.statusBar().showMessage(f"已创建文件夹「{folder}」", 5000)

    def _rename_selected_folder(self, _checked: bool = False, folder: str | None = None) -> None:
        old = folder if folder is not None else self._selected_folder_path()
        if not old:
            return
        text, ok = QInputDialog.getText(self, "重命名文件夹", "新路径：", text=old)
        if not ok:
            return
        new = normalize_folder(text)
        if not new or new == old:
            return
        count = self.store.folder_note_count(old)
        if count:
            answer = QMessageBox.question(
                self,
                "重命名文件夹",
                f"重命名「{old}」为「{new}」？\n其下 {count} 条便签（含子文件夹）将一并移动。",
            )
            if answer != QMessageBox.StandardButton.Yes:
                return
        affected = self.store.rename_folder(old, new)
        self._update_local_folders_after_rename(old, new)
        self._follow_selected_folder_after_rewrite(old, new)
        self.refresh_all()
        self.statusBar().showMessage(f"已重命名文件夹，更新 {affected} 条便签", 5000)
        if count or affected:
            self._notify_local_change()

    def _delete_selected_folder(self, _checked: bool = False, folder: str | None = None) -> None:
        path = folder if folder is not None else self._selected_folder_path()
        if not path:
            return
        count = self.store.folder_note_count(path)
        parent = folder_parent(path)
        hint = f"便签将移至「{parent}」" if parent else "便签将移至「未分类」"
        answer = QMessageBox.question(
            self,
            "删除文件夹",
            f"删除文件夹「{path}」？\n{count} 条便签（含子文件夹）不会删除，{hint}。",
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        affected = self.store.delete_folder(path)
        self._update_local_folders_after_rename(path, parent)
        self._follow_selected_folder_after_rewrite(path, parent)
        self.refresh_all()
        self.statusBar().showMessage(f"已删除文件夹，{affected} 条便签移至上级", 5000)
        if affected:
            self._notify_local_change()

    def _build_trash_page(self) -> QWidget:
        page = QWidget()
        root = QVBoxLayout(page)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        top = QWidget()
        top.setObjectName("TopBar")
        top_layout = QHBoxLayout(top)
        top_layout.setContentsMargins(14, 10, 14, 10)
        top_layout.setSpacing(8)
        back = tool_button("←", "返回便签列表")
        back.clicked.connect(self._show_list)
        title = QLabel("回收站")
        title.setObjectName("PageTitle")
        empty_button = push_button("清空回收站", "Danger")
        empty_button.clicked.connect(self._empty_trash)
        top_layout.addWidget(back)
        top_layout.addWidget(title)
        top_layout.addStretch(1)
        top_layout.addWidget(empty_button)
        root.addWidget(top)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.Shape.NoFrame)
        container = QWidget()
        container_layout = QVBoxLayout(container)
        container_layout.setContentsMargins(0, 0, 0, 0)
        container_layout.setSpacing(0)
        self.trash_empty_label = QLabel("回收站是空的")
        self.trash_empty_label.setObjectName("SubText")
        self.trash_empty_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.trash_empty_label.setContentsMargins(0, 60, 0, 0)
        flow_host = QWidget()
        self.trash_layout = FlowLayout(flow_host, margin=16, spacing=12)
        container_layout.addWidget(self.trash_empty_label)
        container_layout.addWidget(flow_host)
        container_layout.addStretch(1)
        scroll.setWidget(container)
        root.addWidget(scroll, 1)
        return page

    def _setup_tray(self) -> None:
        if not QSystemTrayIcon.isSystemTrayAvailable():
            return
        self._tray = QSystemTrayIcon(theme_mod.make_app_icon(), self)
        self._tray.setToolTip("FreeNote")
        menu = QMenu()
        show_action = menu.addAction("打开 FreeNote")
        show_action.triggered.connect(self._restore_from_tray)
        sync_action = menu.addAction("立即同步")
        sync_action.triggered.connect(self._sync_now)
        clipboard_action = menu.addAction("从剪贴板新建便签")
        clipboard_action.triggered.connect(self._quick_capture_from_clipboard)
        hotkey_hint = menu.addAction("全局热键：Ctrl+Alt+N 新建便签")
        hotkey_hint.setEnabled(False)
        menu.addSeparator()
        quit_action = menu.addAction("退出")
        quit_action.triggered.connect(self._quit_app)
        self._tray.setContextMenu(menu)
        self._tray.activated.connect(self._on_tray_activated)
        self._tray.messageClicked.connect(self._open_last_notified_note)
        self._tray.show()

    def _open_last_notified_note(self) -> None:
        """点击提醒通知时打开对应便签（tray 消息不带来源，只能记住最近一条）。"""
        note_id = self._last_notified_note_id
        self._last_notified_note_id = None
        if not note_id:
            return
        if self.store.get(note_id) is None:
            return
        self._show_list()
        self._open_note(note_id)

    def _on_tray_activated(self, reason: QSystemTrayIcon.ActivationReason) -> None:
        if reason in (
            QSystemTrayIcon.ActivationReason.Trigger,
            QSystemTrayIcon.ActivationReason.DoubleClick,
        ):
            self._restore_from_tray()

    def _restore_from_tray(self) -> None:
        self.showNormal()
        self.raise_()
        self.activateWindow()

    def _quit_app(self) -> None:
        self._force_quit = True
        if not self.close():
            self._force_quit = False

    def _setup_shortcuts(self) -> None:
        QShortcut(QKeySequence("Ctrl+N"), self, self._new_note)
        QShortcut(QKeySequence("Ctrl+F"), self, self._focus_search)
        QShortcut(QKeySequence("Ctrl+S"), self, self._save_editor)
        QShortcut(QKeySequence("Ctrl+,"), self, self._open_settings)

    # ------------------------------------------------------------------ 主题

    def _apply_theme(self) -> None:
        self._theme = theme_mod.effective_theme(self.settings.theme)
        app = QApplication.instance()
        if app is not None:
            app.setStyleSheet(theme_mod.build_qss(self._theme))
        self.editor.set_theme(self._theme)
        self.refresh_all()

    def _on_theme_selected(self, mode: str) -> None:
        self.settings.theme = mode
        self._apply_theme()

    # ------------------------------------------------------------------ 列表刷新

    def refresh_all(self) -> None:
        self._refresh_folders()
        self._refresh_tags()
        self._refresh_cards()
        self._refresh_trash()

    def _refresh_cards(self) -> None:
        # 重建卡片会重置滚动位置：先记录、重建后恢复，避免从列表中部打开便签再返回时跳回顶部
        scrollbar = self.cards_scroll.verticalScrollBar()
        saved_scroll = scrollbar.value()
        query = self.search_edit.text().strip()
        folder = self._selected_folder
        if query:
            notes = self.store.search(query)
            # 锁定便签解锁态搜索：会话 PIN 存在时内存解密匹配（结果按 updatedAt 缓存）
            extra = self._search_locked_notes(query)
            if extra:
                seen_ids = {note.id for note in notes}
                notes.extend(note for note in extra if note.id not in seen_ids)
            if folder is not None:
                notes = [n for n in notes if folder_in_subtree(n.folder, folder)]
            if self._selected_tag:
                notes = [n for n in notes if self._selected_tag in n.tag_list()]
        elif folder is not None:
            notes = self.store.notes_in_folder(folder)
            if self._selected_tag:
                notes = [n for n in notes if self._selected_tag in n.tag_list()]
        elif self._selected_tag:
            notes = self.store.notes_by_tag(self._selected_tag)
        else:
            notes = self.store.list_active()

        notes = self._sorted(notes)
        self._visible_note_ids = [note.id for note in notes]
        if self._batch_mode:
            # 筛选条件变化后，已不可见的便签自动移出选择集
            self._batch_selected &= set(self._visible_note_ids)
        clear_layout(self.cards_layout)
        current_day_label: str | None = None
        for note in notes:
            if self._timeline_mode:
                label_text = self._day_group_label(note.updated_at)
                if label_text != current_day_label:
                    header = QLabel(label_text)
                    header.setObjectName("SubText")
                    self.cards_layout.addWidget(header)
                    current_day_label = label_text
            card = NoteCard(note, self._theme)
            if self._batch_mode:
                card.clicked.connect(self._toggle_card_selection)
                card.set_batch_selected(note.id in self._batch_selected)
            else:
                card.clicked.connect(self._open_note)
            card.pin_toggled.connect(self._toggle_pin)
            card.delete_requested.connect(self._trash_note)
            self.cards_layout.addWidget(card)
        self.empty_label.setVisible(not notes)
        if self._batch_mode:
            self._update_selection_label()
        if not notes:
            if query or self._selected_tag:
                self.empty_label.setText("没有匹配的便签")
            elif folder is not None:
                self.empty_label.setText(f"文件夹「{folder or '未分类'}」下暂无便签")
            else:
                self.empty_label.setText("暂无便签，点击右上角「新建便签」开始记录")
        scrollbar.setValue(min(saved_scroll, scrollbar.maximum()))

    def _search_locked_notes(self, query: str) -> list[Note]:
        """锁定便签的解锁态搜索：只读内存缓存，未缓存的交给后台解密（不阻塞 UI）。

        解密结果按 updatedAt 缓存，便签被修改后自动重新解密；PIN 会话过期后
        锁定便签不再参与搜索（与 Android 端行为一致）。v2 密文每条要 600000 次
        PBKDF2，绝不放在 GUI 线程计算，缺失的结果由后台线程补算后刷新列表。
        """
        pin = self._session.get_pin()
        if pin is None:
            return []
        needle = query.casefold()
        matches: list[Note] = []
        pending = False
        for note in self.store.list_active():
            if not note.is_locked:
                continue
            cached = self._locked_decrypt_cache.get(note.id)
            if cached is None or cached[0] != note.updated_at:
                if (note.id, note.updated_at) not in self._locked_decrypt_failed:
                    pending = True
                continue
            _, title, content = cached
            if needle in title.casefold() or needle in content.casefold():
                matches.append(note)
        if pending:
            self._ensure_locked_decrypts(pin)
        return matches

    def _ensure_locked_decrypts(self, pin: str) -> None:
        """启动一次后台全量补算（已在运行则复用），完成后刷新列表展示匹配结果。"""
        if self._locked_decrypt_worker is not None and self._locked_decrypt_worker.isRunning():
            return
        worker = LockedDecryptWorker(
            self.store, pin, self._locked_decrypt_cache, self._locked_decrypt_failed, self
        )
        worker.finished.connect(self._on_locked_decrypts_done)
        self._locked_decrypt_worker = worker
        worker.start()

    def _on_locked_decrypts_done(self) -> None:
        # 只清理当前这轮的引用：旧 worker 的收尾信号可能晚于新 worker 的启动
        if self.sender() is self._locked_decrypt_worker:
            self._locked_decrypt_worker = None
        self._refresh_cards()

    def _sorted(self, notes: list[Note]) -> list[Note]:
        if self._sort_mode == "created":
            key = lambda n: n.created_at  # noqa: E731
        elif self._sort_mode == "title":
            key = lambda n: n.title.casefold()  # noqa: E731
        else:
            key = lambda n: n.updated_at  # noqa: E731
        descending = self._sort_desc
        return sorted(notes, key=lambda n: (not n.is_pinned, -key(n) if descending else key(n)))

    def _set_sort(self, mode: str) -> None:
        self._sort_mode = mode
        for key, action in self._sort_actions.items():
            action.setChecked(key == mode)
        # 切换排序字段时恢复该字段的常用方向：时间新→旧，标题 A→Z
        self._sort_desc = mode != "title"
        self._sort_desc_action.setChecked(self._sort_desc)
        self._refresh_cards()

    def _set_sort_direction(self, descending: bool) -> None:
        self._sort_desc = bool(descending)
        self._refresh_cards()

    def _set_timeline_mode(self, enabled: bool) -> None:
        """时间线视图：按修改日期分组；开启时切到按修改时间排序，分组才有意义。"""
        self._timeline_mode = bool(enabled)
        if self._timeline_mode:
            self._set_sort("updated")
        else:
            self._refresh_cards()

    def _day_group_label(self, stamp_ms: int) -> str:
        today = datetime.now().date()
        day = datetime.fromtimestamp(stamp_ms / 1000).date()
        if day == today:
            return "今天"
        if (today - day).days == 1:
            return "昨天"
        if day.year == today.year:
            return f"{day.month}月{day.day}日"
        return f"{day.year}年{day.month}月{day.day}日"

    def _refresh_tags(self) -> None:
        tags = self.store.all_tags()
        clear_layout(self.tag_layout)
        chips: list[tuple[str, str | None]] = [("全部", None)] + [(f"#{t}", t) for t in tags]
        for text, tag in chips:
            chip = push_button(text, "Chip")
            chip.setCheckable(True)
            chip.setChecked(self._selected_tag == tag)
            chip.clicked.connect(lambda _=False, t=tag: self._select_tag(t))
            self.tag_layout.addWidget(chip)

    def _select_tag(self, tag: str | None) -> None:
        self._selected_tag = None if self._selected_tag == tag else tag
        self._refresh_tags()
        self._refresh_cards()

    def _refresh_trash(self) -> None:
        notes = self.store.list_trash()
        clear_layout(self.trash_layout)
        for note in notes:
            card = NoteCard(note, self._theme, trash_mode=True)
            card.clicked.connect(lambda _id: None)
            card.restore_requested.connect(self._restore_note)
            card.purge_requested.connect(self._purge_note)
            self.trash_layout.addWidget(card)
        self.trash_empty_label.setVisible(not notes)

    # ------------------------------------------------------------------ 页面

    def _show_list(self) -> None:
        was_dirty = self._list_dirty
        self.stack.setCurrentWidget(self.list_page)
        self._list_dirty = False
        self.refresh_all()
        if was_dirty and (self.search_edit.text().strip() or self._selected_tag or self._selected_folder is not None):
            # 保存的便签可能不匹配当前搜索/标签/文件夹筛选：解释「看不到刚保存的便签」的原因
            self.statusBar().showMessage("便签已保存；当前搜索、标签或文件夹筛选下可能不会显示", 6000)

    def _show_trash(self) -> None:
        self._refresh_trash()
        self.stack.setCurrentWidget(self.trash_page)

    def _focus_search(self) -> None:
        self.stack.setCurrentWidget(self.list_page)
        if self._list_dirty:
            self._list_dirty = False
            self.refresh_all()
        self.search_edit.setFocus()
        self.search_edit.selectAll()

    def _save_editor(self) -> None:
        if self.stack.currentWidget() is self.editor:
            try:
                self.editor.save()
            except Exception as exc:  # noqa: BLE001
                self.statusBar().showMessage(f"保存失败：{exc}", 8000)

    # ------------------------------------------------------------------ 便签操作

    def _new_note(self) -> None:
        if self.editor.load(None, self._selected_folder or "") == "cancelled":
            return
        self.stack.setCurrentWidget(self.editor)

    def _on_hotkey(self) -> None:
        """全局热键：唤起窗口并直接进入新建便签。"""
        self._restore_from_tray()
        self._new_note()

    def _start_hotkey(self) -> None:
        """按设置启动全局热键；被其他程序占用（注册失败）时给出可见提示。"""
        self._stop_hotkey()
        if not self.settings.hotkey_enabled:
            return
        hotkey = GlobalHotkey(self.hotkey_pressed.emit)
        hotkey.start()
        self._hotkey = hotkey
        QTimer.singleShot(1500, self, self._check_hotkey_registered)

    def _check_hotkey_registered(self) -> None:
        hotkey = self._hotkey
        if hotkey is not None and not hotkey.registered:
            self._notify(
                "FreeNote 全局热键不可用",
                "Ctrl+Alt+N 可能被其他程序占用；可在设置中关闭该热键。",
            )

    def _stop_hotkey(self) -> None:
        hotkey = self._hotkey
        self._hotkey = None
        if hotkey is not None:
            hotkey.stop()

    def _on_hotkey_enabled_changed(self, enabled: bool) -> None:
        self.settings.hotkey_enabled = enabled
        self._start_hotkey()

    def _quick_capture_from_clipboard(self) -> None:
        """从剪贴板快速新建便签：首行作为标题，其余作为正文。"""
        clipboard = QApplication.clipboard()
        text = clipboard.text() if clipboard is not None else ""
        text = (text or "").strip()
        if not text:
            self.statusBar().showMessage("剪贴板为空，未创建便签", 4000)
            return
        lines = text.splitlines()
        first = lines[0].strip()
        if len(lines) > 1:
            title = first[:60] or "剪贴板便签"
            content = "\n".join(lines[1:]).strip()
        else:
            title = first[:24] or "剪贴板便签"
            content = first
        note = Note(title=title, content=content)
        self.store.save(note)
        self._notify_local_change()
        self.refresh_all()
        self._notify("FreeNote", f"已从剪贴板新建便签：{title}")

    # ------------------------------------------------------------------ 自动备份

    def _maybe_auto_backup(self) -> None:
        """到点则后台执行自动备份（不阻塞界面，失败静默提示）。"""
        worker = self._auto_backup_thread
        if worker is not None and worker.is_alive():
            return
        if not self.settings.auto_backup_ready():
            return
        self._auto_backup_thread = threading.Thread(
            target=self._run_auto_backup, name="freenote-autobackup", daemon=True
        )
        self._auto_backup_thread.start()

    def _run_auto_backup(self) -> None:
        try:
            result = autobackup.run_auto_backup(self.store, self.settings)
        except Exception as exc:  # noqa: BLE001 - 自动备份失败绝不打断应用
            result = autobackup.AutoBackupResult(False, f"自动备份异常：{exc}")
        if result is None:
            return
        self.auto_backup_done.emit(result.ok, result.message)

    def _on_auto_backup_done(self, ok: bool, message: str) -> None:
        if ok:
            self.statusBar().showMessage(message, 8000)
        else:
            self._notify("FreeNote 自动备份", message)

    def _open_wiki_link(self, title: str) -> None:
        """预览中点击 [[标题]]：打开同名便签（未找到时给出提示）。"""
        note_id = self.store.find_by_title(title)
        if note_id is None:
            self.statusBar().showMessage(f"未找到标题为「{title}」的便签", 6000)
            return
        if self.editor.prepare_close():
            self._show_list()
            self._open_note(note_id)

    def _open_note(self, note_id: str) -> None:
        note = self.store.get(note_id)
        if note is None:
            self.refresh_all()
            return
        if note.is_locked and self._session.get_pin() is None:
            pin = PinDialog.request(self, self.settings, "解锁便签", "此便签已加密，请输入应用锁 PIN")
            if pin is None:
                return
            self._session.set_pin(pin)

        status = self.editor.load(note_id)
        if status == "cancelled":
            return
        if status == "needs_pin":
            pin = PinDialog.request(self, self.settings, "解锁便签", "此便签已加密，请输入应用锁 PIN")
            if pin is None:
                return
            self._session.set_pin(pin)
            status = self.editor.load(note_id)
            if status == "cancelled":
                return
        if status == "bad_pin":
            QMessageBox.warning(self, "无法解密", "PIN 不正确，或该便签来自使用其他 PIN 的设备，无法打开。")
            self._session.clear()
            self._show_list()
            return
        if status == "missing":
            QMessageBox.warning(self, "提示", "便签不存在或已被删除。")
            self.refresh_all()
            return
        self.stack.setCurrentWidget(self.editor)

    def _on_note_saved(self, _note_id: str) -> None:
        # 编辑页的自动保存不必立即重建列表（页面不可见）：标脏，等回到列表时统一刷新，
        # 避免编辑期间每次自动保存都做一次全量重建
        if self.stack.currentWidget() is self.editor:
            self._list_dirty = True
        else:
            self.refresh_all()
        self._notify_local_change()

    def _on_note_deleted(self, note_id: str) -> None:
        # 移到回收站：保留正文引用的内联图片文件，彻底删除/清空回收站时才清理
        self._show_list()
        self._notify_local_change()

    def _notify_local_change(self) -> None:
        self.engine.notify_local_change()
        if self.settings.server_auto_sync and self.settings.server_ready():
            self._server_change_timer.start(3000)

    def _toggle_pin(self, note_id: str) -> None:
        note = self.store.get(note_id)
        if note is None:
            return
        self.store.set_pinned(note_id, not note.is_pinned)
        self._refresh_cards()
        self._notify_local_change()

    def _trash_note(self, note_id: str) -> None:
        if QMessageBox.question(
            self,
            "删除便签",
            "将便签移到回收站？（30 天后自动清除）",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        ) != QMessageBox.StandardButton.Yes:
            return
        self.store.move_to_trash(note_id)
        self.refresh_all()
        self._notify_local_change()

    # ------------------------------------------------------------------ 批量操作

    def _set_batch_mode(self, enabled: bool) -> None:
        self._batch_mode = bool(enabled)
        self._batch_selected.clear()
        self.selection_bar.setVisible(self._batch_mode)
        self._refresh_cards()
        if self._batch_mode:
            self.statusBar().showMessage("多选模式：点击便签卡片选择，使用操作栏批量处理", 6000)

    def _toggle_card_selection(self, note_id: str) -> None:
        if note_id in self._batch_selected:
            self._batch_selected.discard(note_id)
        else:
            self._batch_selected.add(note_id)
        self._refresh_cards()

    def _update_selection_label(self) -> None:
        count = len(self._batch_selected)
        self.selection_label.setText(f"已选 {count} 条")
        for button in (
            self.selection_trash_button,
            self.selection_folder_button,
            self.selection_tag_button,
        ):
            button.setEnabled(count > 0)

    def _batch_select_all(self) -> None:
        self._batch_selected = set(self._visible_note_ids)
        self._refresh_cards()

    def _batch_finish(self, message: str) -> None:
        # 取消勾选会触发 _set_batch_mode(False)：退出多选、隐藏操作栏并刷新
        self.batch_button.setChecked(False)
        self.refresh_all()
        self._notify_local_change()
        self.statusBar().showMessage(message, 6000)

    def _batch_trash(self) -> None:
        count = len(self._batch_selected)
        if count == 0:
            return
        if QMessageBox.question(
            self,
            "删除便签",
            f"将选中的 {count} 条便签移到回收站？（30 天后自动清除）",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        ) != QMessageBox.StandardButton.Yes:
            return
        moved = 0
        for note_id in sorted(self._batch_selected):
            self.store.move_to_trash(note_id)
            moved += 1
        self._batch_finish(f"已将 {moved} 条便签移到回收站")

    def _batch_move_folder(self) -> None:
        if not self._batch_selected:
            return
        choices = ["（根目录）", *self._all_folder_paths()]
        target, ok = QInputDialog.getItem(
            self,
            "移动文件夹",
            "目标文件夹（可直接输入新路径，/ 分隔多级）",
            choices,
            0,
            True,
        )
        if not ok:
            return
        folder = "" if target.strip() == "（根目录）" else normalize_folder(target)
        moved = 0
        for note_id in sorted(self._batch_selected):
            note = self.store.get(note_id)
            if note is None:
                continue
            note.folder = folder
            self.store.save(note)
            moved += 1
        self._batch_finish(f"已移动 {moved} 条便签到「{folder or '未分类'}」")

    def _batch_add_tags(self) -> None:
        if not self._batch_selected:
            return
        text, ok = QInputDialog.getText(self, "添加标签", "标签（多个用逗号或空格分隔）")
        if not ok or not text.strip():
            return
        new_tags = [tag for tag in re.split(r"[，,\s]+", text.strip()) if tag]
        if not new_tags:
            return
        changed = 0
        for note_id in sorted(self._batch_selected):
            note = self.store.get(note_id)
            if note is None:
                continue
            existing = [tag for tag in note.tags.split(",") if tag]
            merged = existing + [tag for tag in new_tags if tag not in existing]
            if merged != existing:
                note.tags = ",".join(merged)
                self.store.save(note)
                changed += 1
        self._batch_finish(f"已为 {changed} 条便签添加标签：{'、'.join(new_tags)}")

    def _restore_note(self, note_id: str) -> None:
        self.store.restore(note_id)
        self.refresh_all()
        self._notify_local_change()

    def _purge_note(self, note_id: str) -> None:
        if QMessageBox.question(
            self,
            "彻底删除",
            "永久删除这条便签？此操作不可撤销。",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        ) != QMessageBox.StandardButton.Yes:
            return
        note = self.store.get(note_id)
        self.store.delete_hard(note_id)
        if note is not None:
            self._delete_note_images(note)
        self.refresh_all()
        self._notify_local_change()

    def _empty_trash(self) -> None:
        trashed = self.store.list_trash()
        if not trashed:
            return
        if QMessageBox.question(
            self,
            "清空回收站",
            f"永久删除 {len(trashed)} 条便签？此操作不可撤销。",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        ) != QMessageBox.StandardButton.Yes:
            return
        for note in self.store.empty_trash():
            self._delete_note_images(note)
        self.refresh_all()
        self._notify_local_change()

    def _purge_trash(self) -> None:
        expired = self.store.purge_expired(now_ms() - config.TRASH_RETENTION_MS)
        for note in expired:
            self._delete_note_images(note)
        if expired:
            self.statusBar().showMessage(f"已自动清除 {len(expired)} 条过期回收站便签", 5000)
            self._notify_local_change()

    def _migrate_legacy_images(self) -> None:
        """后台把 legacy file:// 图片引用迁移为内容寻址的便携标记。"""
        try:
            _scanned, notes, images = attachments.migrate_store(self.store)
        except Exception:  # noqa: BLE001 - 迁移失败不影响启动与同步
            return
        if notes:
            self.images_migrated.emit(images)

    def _after_image_migration(self, images: int) -> None:
        self.statusBar().showMessage(
            f"已迁移 {images} 张图片为可同步格式，将随下次同步上传", 6000
        )
        self.refresh_all()
        self._notify_local_change()

    def _delete_note_images(self, note: Note) -> None:
        attachments_dir = config.attachments_dir().resolve()
        legacy_paths: list[Path] = []
        names: set[str] = set()
        for token_path in parse_image_tokens(note.content):
            name = attachment_name_from_path(token_path)
            if name:
                names.add(name)
                continue
            try:
                candidate = Path(local_image_path(token_path)).resolve()
                if candidate.parent == attachments_dir:
                    legacy_paths.append(candidate)
            except OSError:
                continue
        for candidate in legacy_paths:
            try:
                candidate.unlink(missing_ok=True)
            except OSError:
                continue
        if names:
            # 内容寻址的附件可能被其他便签共用：仅在没有任何便签引用时才删除
            attachments.delete_unused(names, self.store)

    # ------------------------------------------------------------------ 提醒

    def _check_reminders(self) -> None:
        due = self.store.due_reminders()
        if not due:
            return
        for note in due:
            title, body = self._reminder_text(note)
            self._last_notified_note_id = note.id
            self._notify(title, body)
            if note.recurrence:
                # 重复提醒：到点后立刻排下一次（以当前时间为基准，避免过期后连环补发）
                next_ts = next_occurrence(now_ms(), note.recurrence)
                self.store.set_reminder(note.id, next_ts, touch=True)
            else:
                self.store.set_reminder(note.id, None, touch=True)
        self._refresh_cards()

    def _reminder_text(self, note: Note) -> tuple[str, str]:
        if note.is_locked:
            pin = self._session.get_pin()
            if pin:
                title = crypto.decrypt(note.title, pin)
                content = crypto.decrypt(note.content, pin)
                if title is not None:
                    return f"⏰ {title or '加密便签'}", strip_image_tokens(content or "")[:80]
            return "⏰ 加密便签", "锁定便签的提醒（解锁后查看内容）"
        return f"⏰ {note.title or '无标题便签'}", strip_image_tokens(note.content)[:80]

    def _notify(self, title: str, body: str) -> None:
        if self._tray is not None:
            self._tray.showMessage(title, body, QSystemTrayIcon.MessageIcon.Information, 8000)
        else:
            self.statusBar().showMessage(f"{title} {body}", 15000)

    # ------------------------------------------------------------------ P2P 同步

    def _bridge_emit(self, name: str, payload: dict) -> None:
        """引擎线程的事件 → Qt 信号（跨线程自动排队）。"""
        if name == "peers":
            self._bridge.peers_changed.emit()
        elif name == "sync_result":
            self._bridge.sync_result.emit(payload.get("result"))
        elif name == "summary":
            self._bridge.summary.emit(payload.get("summary"))
        elif name == "status":
            self._bridge.status.emit(str(payload.get("text") or ""))
        elif name == "error":
            self._bridge.error.emit(str(payload.get("text") or ""))

    def _on_peers_changed(self) -> None:
        self._refresh_peer_count()
        self._refresh_peers_in_dialog()

    def _on_sync_result(self, result: object) -> None:
        if not isinstance(result, PeerSyncResult):
            return
        stamp = datetime.now().strftime("%H:%M:%S")
        if result.ok:
            name = result.peer_name or result.peer_id
            message = f"与「{name}」{result.message}（{stamp}）"
            if result.pulled:
                self.refresh_all()
        else:
            message = f"同步失败：{result.message}"
        self._present_sync_message(message)

    def _on_summary(self, summary: object) -> None:
        if not isinstance(summary, SyncSummary):
            return
        stamp = datetime.now().strftime("%H:%M:%S")
        message = f"{summary.message}（{stamp}）"
        if summary.errors:
            message += " · " + "；".join(summary.errors)
        if summary.pulled:
            self.refresh_all()
        self._present_sync_message(message)

    def _present_sync_message(self, message: str) -> None:
        """跳过来自引擎事件与手动 worker 的重复消息（两者内容一致）。"""
        if message == self._last_shown_message:
            return
        self._last_shown_message = message
        self._set_sync_status(message)
        self.statusBar().showMessage(message, 8000)

    def _on_engine_status(self, text: str) -> None:
        if text:
            self._present_sync_message(text)

    def _auto_sync_enabled(self) -> bool:
        """任一通道的自动同步开启且可用（与自动同步定时器/启动同步共用判断）。"""
        return (self.settings.auto_sync and self.settings.p2p_ready()) or (
            self.settings.server_auto_sync and self.settings.server_ready()
        )

    def _restart_auto_timer(self) -> None:
        if self._auto_sync_enabled():
            self.auto_timer.start(self.settings.sync_interval_min * 60_000)
        else:
            self.auto_timer.stop()

    def _set_sync_status(self, text: str) -> None:
        self._last_sync_status = text
        if self._settings_dialog is not None:
            self._settings_dialog.set_sync_status(text)

    def _refresh_peer_count(self) -> None:
        count = len(self.engine.peers())
        server = "已登录" if self.settings.server_ready() else "未登录"
        self.sync_button.setToolTip(f"立即同步（P2P 设备 {count} 台 · 服务器{server}）")

    def _sync_now(self, silent: bool = False) -> None:
        """工具栏/设置里的「立即同步」：P2P 与服务器一起同步（可用的通道都走）。"""
        if self._sync_worker is not None and self._sync_worker.isRunning():
            return
        p2p_active = self.settings.p2p_ready() and bool(self.engine.peers())
        server_active = self.settings.server_ready()
        if not p2p_active and not server_active:
            if not silent:
                self.statusBar().showMessage(self._sync_unavailable_message(), 8000)
            return
        self._start_unified_sync(include_p2p=p2p_active, include_server=server_active, silent=silent)

    def _sync_unavailable_message(self) -> str:
        p2p_configured = self.settings.p2p_ready()
        server_configured = self.settings.server_ready()
        if p2p_configured and not self.engine.peers() and not server_configured:
            return "暂无可同步的设备：等待局域网发现，或在设置中手动添加；也可登录服务器后跨网络同步"
        if not p2p_configured and not server_configured:
            return "尚未配置同步：请在「设置」中填写 P2P 同步口令，或登录 FreeNote 服务器"
        return "服务器未登录或没有可用的同步设备"

    def _start_unified_sync(
        self,
        include_p2p: bool,
        include_server: bool,
        peer_id: str = "",
        silent: bool = False,
    ) -> None:
        if self._sync_worker is not None and self._sync_worker.isRunning():
            return
        if self._change_pin_worker is not None and self._change_pin_worker.isRunning():
            if not silent:
                self.statusBar().showMessage("正在修改 PIN，请稍候", 8000)
            return
        self.sync_button.setEnabled(False)
        if not silent:
            self._set_sync_status("同步中…")
            self.statusBar().showMessage("同步中…")
        worker = UnifiedSyncWorker(
            self.engine,
            self.server_client,
            peer_id=peer_id,
            include_p2p=include_p2p,
            include_server=include_server,
            parent=self,
        )
        worker.done.connect(self._on_unified_done)
        worker.finished.connect(lambda: self._on_sync_finished(worker))
        self._sync_worker = worker
        worker.start()

    def _server_sync_async(self, force: bool = False) -> None:
        """本地修改后的延迟服务器同步（静默，不打断用户）。"""
        if not force and not (self.settings.server_auto_sync and self.settings.server_ready()):
            return
        if self._change_pin_worker is not None and self._change_pin_worker.isRunning():
            if force:
                self.statusBar().showMessage("正在修改 PIN，请稍候", 8000)
            return
        if self._sync_worker is not None and self._sync_worker.isRunning():
            # 已有同步在跑：稍后再试一次，让本地修改赶上这轮之后
            self._server_change_timer.start(5000)
            return
        self._start_unified_sync(include_p2p=False, include_server=True, silent=True)

    def _on_unified_done(self, p2p_result: object, server_result: object) -> None:
        stamp = datetime.now().strftime("%H:%M:%S")
        parts: list[str] = []
        refreshed = False
        if isinstance(p2p_result, SyncSummary):
            parts.append(p2p_result.message)
            if p2p_result.errors:
                parts.append("；".join(p2p_result.errors))
            refreshed = refreshed or bool(p2p_result.pulled)
        elif isinstance(p2p_result, PeerSyncResult):
            if p2p_result.ok:
                name = p2p_result.peer_name or p2p_result.peer_id
                parts.append(f"与「{name}」{p2p_result.message}")
                refreshed = refreshed or bool(p2p_result.pulled)
            else:
                parts.append(f"P2P 同步失败：{p2p_result.message}")
        if isinstance(server_result, SyncResult):
            parts.append(server_result.message)
            refreshed = refreshed or bool(server_result.pulled)
            if not server_result.ok:
                # 令牌失效时客户端会清除登录态：同步刷新设置面板与自动同步开关
                if self._settings_dialog is not None:
                    self._settings_dialog.refresh_server_state()
                self._restart_auto_timer()
        if not parts:
            return
        if refreshed:
            self.refresh_all()
        self._present_sync_message(f"{'；'.join(parts)}（{stamp}）")

    def _on_sync_finished(self, worker: object) -> None:
        self.sync_button.setEnabled(True)
        if self._sync_worker is worker:
            self._sync_worker = None

    # ------------------------------------------------------------------ 设置

    def _open_settings(self) -> None:
        dialog = SettingsDialog(self, self.settings, self.store.count_locked())
        self._settings_dialog = dialog
        dialog.set_sync_status(self._last_sync_status)
        dialog.set_peers(self.engine.peers())
        dialog.theme_selected.connect(self._on_theme_selected)
        dialog.hotkey_enabled_changed.connect(self._on_hotkey_enabled_changed)
        dialog.set_pin_requested.connect(lambda: self._set_pin(dialog))
        dialog.change_pin_requested.connect(lambda: self._change_pin(dialog))
        dialog.clear_pin_requested.connect(lambda: self._clear_pin(dialog))
        dialog.p2p_settings_changed.connect(self._on_p2p_settings_changed)
        dialog.sync_now_requested.connect(lambda: self._sync_now())
        dialog.sync_peer_requested.connect(self._sync_peer)
        dialog.forget_peer_requested.connect(self._forget_peer)
        dialog.add_peer_requested.connect(self._add_peer)
        dialog.auto_sync_changed.connect(self._on_auto_sync_changed)
        dialog.server_fields_changed.connect(self._on_server_fields_changed)
        dialog.server_test_requested.connect(lambda url: self._run_server_auth("test", url))
        dialog.server_register_requested.connect(
            lambda url, user, password: self._run_server_auth("register", url, user, password)
        )
        dialog.server_login_requested.connect(
            lambda url, user, password: self._run_server_auth("login", url, user, password)
        )
        dialog.server_logout_requested.connect(lambda: self._run_server_auth("logout"))
        dialog.server_change_password_requested.connect(
            lambda url, old, new: self._run_server_auth(
                "change_password", url, "", old, new
            )
        )
        dialog.server_delete_account_requested.connect(
            lambda url, password: self._run_server_auth("delete_account", url, "", password)
        )
        dialog.server_auto_sync_changed.connect(self._on_server_auto_sync_changed)
        dialog.server_tls_no_verify_changed.connect(self._on_server_tls_no_verify_changed)
        dialog.export_requested.connect(self._export_backup)
        dialog.import_requested.connect(self._import_backup)
        dialog.text_import_requested.connect(self._import_text_files_dialog)
        dialog.auto_backup_changed.connect(self._maybe_auto_backup)
        dialog.exec()
        self._settings_dialog = None
        if dialog.take_changes():
            self.statusBar().showMessage("设置已保存", 5000)
        self._restart_auto_timer()
        self.refresh_all()

    def _on_p2p_settings_changed(self) -> None:
        """设备名/口令/端口/发现开关/同步账户变化：按需重启服务、清理旧账户设备并重新公告。"""
        restart_needed = self._p2p_service_config_changed()
        account_changed = self._p2p_account_config_changed()
        mode = "restart" if restart_needed else ("account" if account_changed else "")
        if mode:
            self._queue_p2p_apply(mode)
        else:
            self.engine.announce_now()
        self._restart_auto_timer()
        self._refresh_peer_count()
        self._refresh_peers_in_dialog()

    def _queue_p2p_apply(self, mode: str) -> None:
        """在后台线程应用 P2P 设置：口令派生（PBKDF2）与全库摘要都很耗时，不能卡界面。"""
        worker = self._p2p_config_worker
        if worker is not None and worker.isRunning():
            # 上一次还在应用：合并期间的改动，完成后按最新模式再应用一次
            if mode == "restart" or self._p2p_config_pending == "restart":
                self._p2p_config_pending = "restart"
            else:
                self._p2p_config_pending = mode
            return
        if self._settings_dialog is not None:
            self._settings_dialog.set_sync_status("正在应用同步设置…")
        worker = P2PConfigWorker(self.engine, mode, parent=self)
        worker.done.connect(self._on_p2p_apply_done)
        worker.finished.connect(lambda: self._on_p2p_apply_finished(worker))
        self._p2p_config_worker = worker
        worker.start()

    def _on_p2p_apply_done(self, message: str) -> None:
        if self._settings_dialog is not None:
            self._settings_dialog.set_sync_status(message)
        self.statusBar().showMessage(message, 6000)
        self._refresh_peer_count()
        self._refresh_peers_in_dialog()

    def _on_p2p_apply_finished(self, worker: P2PConfigWorker) -> None:
        if self._p2p_config_worker is worker:
            self._p2p_config_worker = None
        pending, self._p2p_config_pending = self._p2p_config_pending, ""
        if pending:
            self._queue_p2p_apply(pending)

    def _p2p_service_config_changed(self) -> bool:
        current = (
            self.settings.p2p_enabled,
            self.settings.sync_port,
            self.settings.discovery_enabled,
            self.settings.group_passphrase,
        )
        if self._p2p_config_snapshot != current:
            self._p2p_config_snapshot = current
            return True
        return False

    def _p2p_account_config_changed(self) -> bool:
        """同步账户（服务器账号或离线账户）变化时返回 True。"""
        current = (
            self.settings.server_url,
            self.settings.server_username,
            self.settings.p2p_account,
        )
        if self._p2p_account_snapshot != current:
            self._p2p_account_snapshot = current
            return True
        return False

    def _refresh_peers_in_dialog(self) -> None:
        if self._settings_dialog is not None:
            self._settings_dialog.refresh_account_state()
            self._settings_dialog.set_peers(self.engine.peers())

    def _sync_peer(self, peer_id: str) -> None:
        self._start_unified_sync(include_p2p=True, include_server=False, peer_id=peer_id)

    # ------------------------------------------------------------------ 服务器同步

    def _run_server_auth(
        self,
        action: str,
        url: str = "",
        username: str = "",
        password: str = "",
        new_password: str = "",
    ) -> None:
        if self._server_auth_worker is not None and self._server_auth_worker.isRunning():
            return
        dialog = self._settings_dialog
        if dialog is not None:
            dialog.set_server_busy(True)
        worker = ServerAuthWorker(
            self.server_client, action, url, username, password, new_password, parent=self
        )
        worker.done.connect(self._on_server_auth_done)
        worker.finished.connect(lambda: self._on_server_auth_finished(worker))
        self._server_auth_worker = worker
        worker.start()

    def _on_server_auth_finished(self, worker: ServerAuthWorker) -> None:
        if self._server_auth_worker is worker:
            self._server_auth_worker = None
        dialog = self._settings_dialog
        if dialog is not None:
            dialog.set_server_busy(False)

    def _on_server_auth_done(self, action: str, ok: bool, message: str, _result: object) -> None:
        dialog = self._settings_dialog
        if dialog is not None:
            dialog.refresh_server_state()
            dialog.set_server_status(message)
            if ok and action in ("register", "login"):
                dialog.clear_server_password()
        self.statusBar().showMessage(message, 8000)
        self._refresh_peer_count()
        self._restart_auto_timer()
        if ok and action in ("register", "login", "logout", "delete_account"):
            # 服务器账号决定 P2P 同步账户：登录/退出/注销后切换账户并重置设备列表
            self._on_p2p_settings_changed()
        if ok and action in ("register", "login"):
            # 登录成功后立刻做一轮服务器同步，把两端数据接起来
            self._server_sync_async(force=True)

    def _on_server_fields_changed(self) -> None:
        self._on_p2p_settings_changed()

    def _on_server_auto_sync_changed(self, enabled: bool) -> None:
        self.settings.server_auto_sync = enabled
        self._restart_auto_timer()

    def _on_server_tls_no_verify_changed(self, enabled: bool) -> None:
        self.settings.server_tls_no_verify = enabled

    def _forget_peer(self, peer_id: str) -> None:
        self.engine.forget_peer(peer_id)
        self._refresh_peers_in_dialog()
        self._refresh_peer_count()

    def _add_peer(self, text: str) -> None:
        if self._add_peer_worker is not None and self._add_peer_worker.isRunning():
            return
        if self._settings_dialog is not None:
            self._settings_dialog.set_sync_status(f"正在连接 {text}…")
        worker = AddPeerWorker(self.engine, text, parent=self)
        worker.done.connect(lambda ok, message: self._on_add_peer_done(ok, message))
        worker.finished.connect(lambda: self._on_add_peer_finished(worker))
        self._add_peer_worker = worker
        worker.start()

    def _on_add_peer_finished(self, worker: AddPeerWorker) -> None:
        if self._add_peer_worker is worker:
            self._add_peer_worker = None

    def _on_add_peer_done(self, ok: bool, message: str) -> None:
        if self._settings_dialog is not None:
            self._settings_dialog.add_peer_result(ok, message)
        self.statusBar().showMessage(message, 8000)
        self._refresh_peers_in_dialog()
        self._refresh_peer_count()

    def _set_pin(self, dialog: SettingsDialog) -> None:
        pin = SetPinDialog.request(self)
        if pin is None:
            return
        self.settings.pin_hash = crypto.hash_pin(pin)
        self._session.set_pin(pin)
        dialog.set_locked_count(self.store.count_locked())
        self.statusBar().showMessage("已设置应用锁 PIN", 5000)

    def _change_pin(self, dialog: SettingsDialog) -> None:
        if not self.settings.pin_hash:
            return
        if self._change_pin_worker is not None and self._change_pin_worker.isRunning():
            return
        old_pin = PinDialog.request(self, self.settings, "修改 PIN", "请输入当前 PIN")
        if old_pin is None:
            return
        new_pin = SetPinDialog.request(self)
        if new_pin is None:
            return
        if (self._sync_worker is not None and self._sync_worker.isRunning()) or (
            self._backup_worker is not None and self._backup_worker.isRunning()
        ):
            self.statusBar().showMessage("正在同步或备份，请稍候再修改 PIN", 8000)
            return
        dialog.set_sync_status("正在用新 PIN 重新加密便签…")
        worker = ChangePinWorker(self.store, old_pin, new_pin, parent=self)
        worker.done.connect(lambda ok, failed, error: self._on_change_pin_done(new_pin, ok, failed, error))
        worker.finished.connect(lambda: self._on_change_pin_finished(worker))
        self._change_pin_worker = worker
        worker.start()

    def _on_change_pin_done(self, new_pin: str, ok: int, failed: int, error: str) -> None:
        if error:
            QMessageBox.warning(self, "修改 PIN", f"修改失败：{error}")
            return
        self.settings.pin_hash = crypto.hash_pin(new_pin)
        self._session.set_pin(new_pin)
        if self._settings_dialog is not None:
            self._settings_dialog.set_locked_count(self.store.count_locked())
        message = f"PIN 已修改，重新加密 {ok} 条便签"
        if failed:
            message += f"；{failed} 条无法解密（可能来自其他 PIN 的设备），保持原样"
        QMessageBox.information(self, "修改 PIN", message)
        self.refresh_all()
        self._notify_local_change()

    def _on_change_pin_finished(self, worker: ChangePinWorker) -> None:
        if self._change_pin_worker is worker:
            self._change_pin_worker = None

    def _clear_pin(self, dialog: SettingsDialog) -> None:
        if not self.settings.pin_hash:
            return
        locked = self.store.count_locked()
        prompt = (
            f"清除后所有已加密便签将无法解锁（当前 {locked} 条）。如需更换 PIN，请使用「修改 PIN」。\n\n输入当前 PIN 确认清除："
            if locked
            else "输入当前 PIN 确认清除："
        )
        pin = PinDialog.request(self, self.settings, "清除 PIN", prompt)
        if pin is None:
            return
        self.settings.pin_hash = None
        self._session.clear()
        dialog.set_locked_count(locked)
        self.statusBar().showMessage("已清除 PIN", 5000)

    def _on_auto_sync_changed(self, enabled: bool, interval_min: int) -> None:
        self.settings.auto_sync = enabled
        self.settings.sync_interval_min = interval_min
        self._restart_auto_timer()

    def _export_backup(self) -> None:
        if self._backup_worker is not None and self._backup_worker.isRunning():
            return
        if self._change_pin_worker is not None and self._change_pin_worker.isRunning():
            self.statusBar().showMessage("正在修改 PIN，请稍候", 8000)
            return
        default = str(Path.home() / f"freenote_backup_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json")
        path, _ = QFileDialog.getSaveFileName(self, "导出便签备份", default, "JSON (*.json)")
        if not path:
            return
        self._start_backup_worker("export", path)

    def _import_text_files_dialog(self) -> None:
        """选择 .md/.txt/.enex 文件导入为便签（每个文本文件一条，ENEX 按内部便签展开）。"""
        paths, _ = QFileDialog.getOpenFileNames(
            self,
            "导入 Markdown / 文本 / Evernote",
            str(Path.home()),
            "文本与导出 (*.md *.markdown *.txt *.enex);;所有文件 (*)",
        )
        if not paths:
            return
        result = backup.import_text_files(self.store, list(paths))
        self.refresh_all()
        self._notify_local_change()
        self.statusBar().showMessage(result.message, 8000)

    def dragEnterEvent(self, event) -> None:  # noqa: N802 - Qt 事件命名
        if event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dropEvent(self, event) -> None:  # noqa: N802 - Qt 事件命名
        """拖拽导入：.md/.txt 文件（或文件夹）与 JSON 备份。"""
        paths = [url.toLocalFile() for url in event.mimeData().urls() if url.isLocalFile()]
        if not paths:
            return
        event.acceptProposedAction()
        texts, backups = backup.collect_import_files(paths)
        messages: list[str] = []
        if texts:
            messages.append(backup.import_text_files(self.store, texts).message)
        if backups:
            # JSON 备份会按 LWW 覆盖本机较旧的便签并让回收站条目重新入队：
            # 与「导入 JSON 备份」按钮一样先确认，避免误拖文件导致静默覆盖
            names = "、".join(Path(path).name for path in backups[:3])
            extra = f" 等 {len(backups)} 个文件" if len(backups) > 3 else ""
            answer = QMessageBox.question(
                self,
                "导入备份",
                f"拖入的内容包含备份文件（{names}{extra}）：\n"
                "备份里较新的便签会覆盖本机旧版本，本机较新的保持不变。继续导入？",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.No,
            )
            if answer != QMessageBox.StandardButton.Yes:
                self.statusBar().showMessage("已取消导入备份", 6000)
            else:
                for backup_path in backups:
                    messages.append(backup.import_notes(self.store, backup_path).message)
        if not messages:
            if backups and not texts:
                # 只有备份文件且用户取消：已给出取消提示，不再报「不是可导入文件」
                return
            self.statusBar().showMessage("拖入的文件不是 .md/.txt/.json，未导入", 6000)
            return
        self.refresh_all()
        self._notify_local_change()
        self.statusBar().showMessage("；".join(messages), 10000)

    def _import_backup(self) -> None:
        if self._backup_worker is not None and self._backup_worker.isRunning():
            return
        if self._change_pin_worker is not None and self._change_pin_worker.isRunning():
            self.statusBar().showMessage("正在修改 PIN，请稍候", 8000)
            return
        path, _ = QFileDialog.getOpenFileName(self, "导入便签备份", str(Path.home()), "JSON (*.json);;所有文件 (*)")
        if not path:
            return
        if QMessageBox.question(
            self,
            "导入备份",
            "将把备份中的便签合并导入：备份里较新的便签会覆盖本机旧版本，本机较新的保持不变。继续？",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        ) != QMessageBox.StandardButton.Yes:
            return
        self._start_backup_worker("import", path)

    def _start_backup_worker(self, action: str, path: str) -> None:
        self.statusBar().showMessage("正在导出备份…" if action == "export" else "正在导入备份…")
        worker = BackupWorker(self.store, action, path, parent=self)
        worker.done.connect(self._on_backup_done)
        worker.finished.connect(lambda: self._on_backup_finished(worker))
        self._backup_worker = worker
        worker.start()

    def _on_backup_done(self, action: str, success: bool, message: str) -> None:
        title = "导出备份" if action == "export" else "导入备份"
        if success:
            QMessageBox.information(self, title, message)
        else:
            QMessageBox.warning(self, title, message)
        if action == "import" and success:
            self.refresh_all()
            self._notify_local_change()

    def _on_backup_finished(self, worker: BackupWorker) -> None:
        if self._backup_worker is worker:
            self._backup_worker = None

    # ------------------------------------------------------------------ 关闭

    def closeEvent(self, event: QCloseEvent) -> None:  # noqa: N802
        if not self.editor.prepare_close():
            event.ignore()
            return
        # 后台任务进行中不能退出：中途关库会让 worker 写库失败，
        # 修改 PIN 还可能留下「部分便签已用新 PIN、哈希仍是旧 PIN」的不可解密状态
        for worker, label in (
            (self._change_pin_worker, "PIN 重新加密"),
            (self._backup_worker, "备份导出/导入"),
            (self._sync_worker, "同步"),
            (self._server_auth_worker, "服务器账号操作"),
            (self._p2p_config_worker, "同步设置应用"),
            (self._add_peer_worker, "添加设备"),
        ):
            if worker is not None and worker.isRunning():
                QMessageBox.information(self, "请稍候", f"{label}正在进行，完成后再退出。")
                event.ignore()
                return
        if self._tray is not None and self._tray.isVisible() and not self._force_quit:
            # 关闭窗口 = 最小化到托盘：托盘图标存在时应让提醒与后台同步继续运行，
            # 否则用户以为只是收起窗口，实际提醒全部错过（退出请用托盘菜单）
            event.ignore()
            self.hide()
            # 每次收起都提示：仅首次提示会让后续用户误以为程序已退出
            self._tray.showMessage(
                "FreeNote 仍在后台运行",
                "窗口已最小化到托盘：提醒与同步会继续运行；右键托盘图标可退出。",
                QSystemTrayIcon.MessageIcon.Information,
                5000,
            )
            return
        self._stop_hotkey()
        self.settings.set("window_width", self.width(), save=False)
        self.settings.set("window_height", self.height(), save=False)
        if not self.isMaximized() and not self.isFullScreen():
            self.settings.set("window_x", self.x(), save=False)
            self.settings.set("window_y", self.y(), save=False)
        self.settings.set("window_maximized", self.isMaximized(), save=False)
        self.settings.save()
        # 防止防抖中的搜索在窗口销毁后触发、访问已关闭的数据库
        self._search_timer.stop()
        event.accept()
