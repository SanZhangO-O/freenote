"""对话框：PIN 输入/设置、颜色选择、提醒时间、设置面板（含 P2P 同步与设备列表）。"""

from __future__ import annotations

import secrets
import uuid

from PySide6.QtCore import QDateTime, Qt, Signal
from PySide6.QtGui import QColor, QRegularExpressionValidator
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QColorDialog,
    QDateTimeEdit,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QGridLayout,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QListWidget,
    QMessageBox,
    QPushButton,
    QRadioButton,
    QScrollArea,
    QSpinBox,
    QTableWidget,
    QTableWidgetItem,
    QTextBrowser,
    QVBoxLayout,
    QWidget,
)

from .. import attachments
from .. import crypto
from .. import __version__ as app_version
from .. import markdown as markdown_mod
from ..models import (
    DEFAULT_COLORS,
    RECURRENCE_CHOICES,
    format_full_time,
    format_note_time,
    normalize_recurrence,
    now_ms,
    strip_image_tokens,
)
from ..settings import Settings
from . import theme as theme_mod
from .widgets import hline, push_button

_PASSPHRASE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def generate_passphrase(groups: int = 4, group_len: int = 4) -> str:
    """生成便于口述/抄写的随机同步口令，如 7F3K-9QMX-2PVD-K5RN。"""
    parts = [
        "".join(secrets.choice(_PASSPHRASE_ALPHABET) for _ in range(group_len))
        for _ in range(groups)
    ]
    return "-".join(parts)


class RevealOnFocusLineEdit(QLineEdit):
    """凭据输入框：默认隐藏，聚焦时显示明文便于核对，失焦后自动隐藏。"""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.setEchoMode(QLineEdit.EchoMode.Password)

    def focusInEvent(self, event) -> None:  # noqa: N802
        super().focusInEvent(event)
        self.setEchoMode(QLineEdit.EchoMode.Normal)

    def focusOutEvent(self, event) -> None:  # noqa: N802
        self.setEchoMode(QLineEdit.EchoMode.Password)
        super().focusOutEvent(event)


def _pin_edit(placeholder: str = "PIN") -> QLineEdit:
    from PySide6.QtCore import QRegularExpression

    edit = QLineEdit()
    edit.setEchoMode(QLineEdit.EchoMode.Password)
    edit.setMaxLength(crypto.PIN_MAX_LENGTH)
    edit.setValidator(QRegularExpressionValidator(QRegularExpression("[0-9]{0,6}")))
    edit.setPlaceholderText(placeholder)
    return edit


class PinDialog(QDialog):
    """输入 PIN 并校验（用于解锁便签 / 启动解锁）。"""

    def __init__(self, parent: QWidget | None, settings: Settings, title: str, prompt: str):
        super().__init__(parent)
        self.settings = settings
        self.setWindowTitle(title)
        self.setMinimumWidth(320)
        layout = QVBoxLayout(self)
        layout.setSpacing(10)
        label = QLabel(prompt)
        label.setWordWrap(True)
        layout.addWidget(label)
        self.edit = _pin_edit()
        self.edit.returnPressed.connect(self._try_accept)
        layout.addWidget(self.edit)
        self.error = QLabel("")
        self.error.setStyleSheet("color: #d9534f;")
        layout.addWidget(self.error)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self._try_accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)
        self.edit.setFocus()

    def _try_accept(self) -> None:
        pin = self.edit.text()
        if not crypto.is_complete_pin(pin):
            self.error.setText("请输入 4~6 位数字 PIN")
            return
        if not crypto.verify_pin(pin, self.settings.pin_hash):
            self.error.setText("PIN 错误，请重试")
            self.edit.selectAll()
            return
        self.accept()

    @property
    def pin(self) -> str:
        return self.edit.text()

    @staticmethod
    def request(parent: QWidget | None, settings: Settings, title: str = "解锁", prompt: str = "请输入应用锁 PIN") -> str | None:
        dialog = PinDialog(parent, settings, title, prompt)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            return dialog.pin
        return None


class SetPinDialog(QDialog):
    """设置新 PIN（两次输入）。"""

    def __init__(self, parent: QWidget | None):
        super().__init__(parent)
        self.setWindowTitle("设置 PIN")
        self.setMinimumWidth(320)
        layout = QVBoxLayout(self)
        layout.setSpacing(10)
        layout.addWidget(QLabel("PIN 为 4~6 位数字，用于加密锁定便签。"))
        self.first = _pin_edit("输入新 PIN")
        self.second = _pin_edit("再次输入")
        layout.addWidget(self.first)
        layout.addWidget(self.second)
        self.error = QLabel("")
        self.error.setStyleSheet("color: #d9534f;")
        layout.addWidget(self.error)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self._try_accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)
        self.first.setFocus()

    def _try_accept(self) -> None:
        first, second = self.first.text(), self.second.text()
        if not crypto.is_complete_pin(first):
            self.error.setText("PIN 需为 4~6 位数字")
            return
        if first != second:
            self.error.setText("两次输入不一致")
            return
        self.accept()

    @property
    def pin(self) -> str:
        return self.first.text()

    @staticmethod
    def request(parent: QWidget | None) -> str | None:
        dialog = SetPinDialog(parent)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            return dialog.pin
        return None


class ColorPickerDialog(QDialog):
    def __init__(self, parent: QWidget | None, current: int, theme: str):
        super().__init__(parent)
        self.setWindowTitle("便签颜色")
        self.selected = current
        layout = QVBoxLayout(self)
        grid = QGridLayout()
        grid.setSpacing(10)
        for index, color in enumerate(DEFAULT_COLORS):
            button = QPushButton()
            button.setFixedSize(40, 40)
            button.setCursor(Qt.CursorShape.PointingHandCursor)
            bg, border, _ = theme_mod.note_card_colors(color, theme)
            frame = "3px solid " + theme_mod.colors(theme)["accent"] if color == current else f"1px solid {border}"
            button.setStyleSheet(f"QPushButton {{ background: {bg}; border: {frame}; border-radius: 8px; }}")
            button.clicked.connect(lambda _=False, value=color: self._choose(value))
            grid.addWidget(button, index // 4, index % 4)
        layout.addLayout(grid)
        custom = push_button("自定义颜色…")
        custom.clicked.connect(self._choose_custom)
        layout.addWidget(custom)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Cancel)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def _choose(self, value: int) -> None:
        self.selected = value
        self.accept()

    def _choose_custom(self) -> None:
        color = QColorDialog.getColor(QColor(self.selected & 0xFFFFFF), self, "选择颜色")
        if color.isValid():
            self.selected = 0xFF000000 | (color.red() << 16) | (color.green() << 8) | color.blue()
            self.accept()

    @staticmethod
    def request(parent: QWidget | None, current: int, theme: str) -> int | None:
        dialog = ColorPickerDialog(parent, current, theme)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            return dialog.selected
        return None


class ReminderDialog(QDialog):
    """返回 ("set", ms, recurrence) / ("clear", None, "") / ("cancel", None, "")。"""

    def __init__(
        self, parent: QWidget | None, current_ms: int | None, current_recurrence: str = ""
    ):
        super().__init__(parent)
        self.setWindowTitle("提醒")
        self.action = "cancel"
        self.value: int | None = None
        self.recurrence = normalize_recurrence(current_recurrence)
        layout = QVBoxLayout(self)
        layout.setSpacing(10)
        layout.addWidget(QLabel("到点后 FreeNote 会弹出系统通知；可设置重复提醒。"))
        self.edit = QDateTimeEdit()
        self.edit.setCalendarPopup(True)
        self.edit.setDisplayFormat("yyyy/MM/dd HH:mm")
        start = QDateTime.fromMSecsSinceEpoch(current_ms) if current_ms else QDateTime.currentDateTime().addSecs(3600)
        self.edit.setDateTime(start)
        layout.addWidget(self.edit)
        recurrence_row = QHBoxLayout()
        recurrence_row.addWidget(QLabel("重复"))
        self.recurrence_combo = QComboBox()
        for value, text in RECURRENCE_CHOICES:
            self.recurrence_combo.addItem(text, value)
        index = max(0, self.recurrence_combo.findData(self.recurrence))
        self.recurrence_combo.setCurrentIndex(index)
        recurrence_row.addWidget(self.recurrence_combo, 1)
        layout.addLayout(recurrence_row)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self._accept)
        buttons.rejected.connect(self.reject)
        clear = buttons.addButton("清除提醒", QDialogButtonBox.ButtonRole.DestructiveRole)
        clear.clicked.connect(self._clear)
        layout.addWidget(buttons)

    def _accept(self) -> None:
        value = int(self.edit.dateTime().toMSecsSinceEpoch())
        # 过去的时间会导致保存后立刻弹一次通知随即消失（一次性提醒），必须先拦下
        if value <= now_ms():
            QMessageBox.warning(
                self, "提醒时间", "提醒时间已过。请选择将来的时间，或用「清除提醒」取消提醒。"
            )
            return
        self.action = "set"
        self.value = value
        self.recurrence = normalize_recurrence(str(self.recurrence_combo.currentData() or ""))
        self.accept()

    def _clear(self) -> None:
        self.action = "clear"
        self.value = None
        self.recurrence = ""
        self.accept()

    @staticmethod
    def request(
        parent: QWidget | None, current_ms: int | None, current_recurrence: str = ""
    ) -> tuple[str, int | None, str]:
        dialog = ReminderDialog(parent, current_ms, current_recurrence)
        dialog.exec()
        return dialog.action, dialog.value, dialog.recurrence


class HistoryDialog(QDialog):
    """历史版本浏览/恢复：左侧版本列表，右侧 Markdown 预览。"""

    def __init__(
        self,
        parent: QWidget | None,
        store,
        note,
        theme: str,
        pin: str | None,
    ):
        super().__init__(parent)
        self.store = store
        self.note = note
        self.theme = theme
        self.pin = pin
        self.restored = False
        self.setWindowTitle("历史版本")
        self.resize(780, 540)

        outer = QVBoxLayout(self)
        hint = QLabel("每次修改会自动留存旧版本（同内容/5 分钟内合并，最多 30 版，仅保存在本机）。")
        hint.setObjectName("SubText")
        hint.setWordWrap(True)
        outer.addWidget(hint)

        split = QHBoxLayout()
        self.version_list = QListWidget()
        self.version_list.setMinimumWidth(240)
        self.preview = QTextBrowser()
        split.addWidget(self.version_list, 1)
        split.addWidget(self.preview, 2)
        outer.addLayout(split, 1)

        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Close)
        buttons.rejected.connect(self.reject)
        restore_button = buttons.addButton("恢复此版本", QDialogButtonBox.ButtonRole.AcceptRole)
        restore_button.clicked.connect(self._restore)
        outer.addWidget(buttons)

        self._versions = list(store.history_versions(note.id))
        if not self._versions:
            self.preview.setHtml("<p>暂无历史版本：便签保存过修改后会自动留存旧版本。</p>")
        for row in self._versions:
            saved = format_full_time(row["saved_at"])
            raw_snippet = (row["title"] or strip_image_tokens(row["content"], "[图片]") or "空便签")
            snippet = raw_snippet.splitlines()[0][:24]
            self.version_list.addItem(f"{saved}\n{snippet}")
        self.version_list.currentRowChanged.connect(self._show_version)
        if self._versions:
            self.version_list.setCurrentRow(0)

    def _show_version(self, index: int) -> None:
        if index < 0 or index >= len(self._versions):
            self.preview.setHtml("")
            return
        row = self._versions[index]
        title = row["title"]
        content = row["content"]
        if row["is_locked"]:
            if not self.pin:
                self.preview.setHtml("<p>该版本属于加密便签；恢复后需解锁查看内容。</p>")
                return
            title = crypto.decrypt(title, self.pin) or "加密便签"
            content = crypto.decrypt(content, self.pin) or ""
        resolved = attachments.resolve_for_render(content)
        html = markdown_mod.render_document(resolved, self.theme)
        self.preview.setHtml(f"<h3>{title or '无标题便签'}</h3>{html}")

    def _restore(self) -> None:
        index = self.version_list.currentRow()
        if index < 0 or index >= len(self._versions):
            return
        row = self._versions[index]
        saved = format_full_time(row["saved_at"])
        answer = QMessageBox.question(
            self,
            "恢复版本",
            f"用 {saved} 的版本覆盖当前内容？（当前内容会先存入历史，可再恢复回来）",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        if self.store.restore_history(self.note.id, int(row["history_id"])):
            self.restored = True
            self.accept()
        else:
            QMessageBox.warning(self, "恢复失败", "该历史版本已不存在")


class SettingsDialog(QDialog):
    theme_selected = Signal(str)
    hotkey_enabled_changed = Signal(bool)
    set_pin_requested = Signal()
    change_pin_requested = Signal()
    clear_pin_requested = Signal()
    p2p_settings_changed = Signal()
    sync_now_requested = Signal()
    sync_peer_requested = Signal(str)
    forget_peer_requested = Signal(str)
    add_peer_requested = Signal(str)
    auto_sync_changed = Signal(bool, int)
    server_fields_changed = Signal()
    server_test_requested = Signal(str)
    server_register_requested = Signal(str, str, str)
    server_login_requested = Signal(str, str, str)
    server_logout_requested = Signal()
    server_change_password_requested = Signal(str, str, str)
    server_delete_account_requested = Signal(str, str)
    server_auto_sync_changed = Signal(bool)
    server_tls_no_verify_changed = Signal(bool)
    export_requested = Signal()
    import_requested = Signal()
    text_import_requested = Signal()
    auto_backup_changed = Signal()

    def __init__(self, parent: QWidget | None, settings: Settings, locked_count: int):
        super().__init__(parent)
        self.settings = settings
        self._changed_any = False
        self.setWindowTitle("设置")
        self.setMinimumWidth(560)
        # 内容放进滚动区：小屏/低分辨率下超出部分可滚动，「关闭」固定在底部
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.Shape.NoFrame)
        content = QWidget(self)
        root = QVBoxLayout(content)
        root.setSpacing(12)
        root.setContentsMargins(12, 12, 12, 12)

        # ------------------------------------------------------------ 外观
        root.addWidget(self._section("外观"))
        theme_row = QHBoxLayout()
        self.theme_buttons: dict[str, QRadioButton] = {}
        for key, text in (("system", "跟随系统"), ("light", "浅色"), ("dark", "深色")):
            radio = QRadioButton(text)
            radio.setChecked(settings.theme == key)
            radio.toggled.connect(lambda checked, k=key: checked and self.theme_selected.emit(k))
            theme_row.addWidget(radio)
            self.theme_buttons[key] = radio
        theme_row.addStretch(1)
        root.addLayout(theme_row)
        self.hotkey_check = QCheckBox("启用全局热键 Ctrl+Alt+N（任意程序里快速新建便签）")
        self.hotkey_check.setChecked(settings.hotkey_enabled)
        self.hotkey_check.setToolTip("与其他程序快捷键冲突时可关闭；关闭后不再全局抢占该组合键")
        self.hotkey_check.toggled.connect(self.hotkey_enabled_changed.emit)
        root.addWidget(self.hotkey_check)

        root.addWidget(hline())
        # ------------------------------------------------------------ 隐私
        root.addWidget(self._section("隐私"))
        pin_row = QHBoxLayout()
        self.pin_label = QLabel()
        pin_row.addWidget(self.pin_label)
        pin_row.addStretch(1)
        self.pin_action = push_button("")
        self.pin_action.clicked.connect(self._on_pin_action)
        pin_row.addWidget(self.pin_action)
        self.clear_pin_button = push_button("清除 PIN", "Danger")
        self.clear_pin_button.clicked.connect(self.clear_pin_requested.emit)
        pin_row.addWidget(self.clear_pin_button)
        root.addLayout(pin_row)
        self._locked_count = locked_count
        self.refresh_pin_state()

        root.addWidget(hline())
        # ------------------------------------------------------------ P2P 同步
        root.addWidget(self._section("P2P 同步（局域网直连，无需服务器）"))
        self.p2p_enable_check = QCheckBox("启用 P2P 同步（默认关闭；关闭时不监听端口、不联网）")
        self.p2p_enable_check.setChecked(settings.p2p_enabled)
        self.p2p_enable_check.toggled.connect(self._apply_fields)
        self.p2p_enable_check.toggled.connect(self._set_p2p_fields_enabled)
        self.p2p_enable_check.setToolTip(
            "网络功能默认关闭：勾选后才会监听同步端口并参与局域网发现/同步"
        )
        root.addWidget(self.p2p_enable_check)
        form = QGridLayout()
        form.setHorizontalSpacing(8)
        form.setVerticalSpacing(8)
        form.addWidget(QLabel("设备名称"), 0, 0)
        self.device_name_edit = QLineEdit(settings.device_name)
        self.device_name_edit.setPlaceholderText("例如：我的笔记本")
        form.addWidget(self.device_name_edit, 0, 1, 1, 2)
        form.addWidget(QLabel("同步端口"), 0, 3)
        self.port_spin = QSpinBox()
        self.port_spin.setRange(0, 65535)
        self.port_spin.setSpecialValueText("自动")
        self.port_spin.setValue(settings.sync_port)
        self.port_spin.setToolTip("每台设备内置同步服务的端口；被占用时自动顺延。0 = 自动分配。")
        self.port_spin.valueChanged.connect(self._apply_fields)
        form.addWidget(self.port_spin, 0, 4)

        form.addWidget(QLabel("同步口令"), 1, 0)
        self.passphrase_edit = RevealOnFocusLineEdit(settings.group_passphrase)
        self.passphrase_edit.setPlaceholderText("同一口令的设备才会互相发现并同步")
        self.passphrase_edit.setToolTip("默认隐藏，点击输入框时显示明文，避免录屏或旁人直视泄露")
        form.addWidget(self.passphrase_edit, 1, 1, 1, 2)
        self.gen_passphrase_button = push_button("生成随机口令")
        self.gen_passphrase_button.clicked.connect(self._confirm_generate_passphrase)
        self.copy_passphrase_button = push_button("复制")
        self.copy_passphrase_button.clicked.connect(self._copy_passphrase)
        form.addWidget(self.gen_passphrase_button, 1, 3)
        form.addWidget(self.copy_passphrase_button, 1, 4)

        form.addWidget(QLabel("离线账户"), 2, 0)
        self.account_edit = QLineEdit(settings.p2p_account)
        self.account_edit.setPlaceholderText("未登录服务器时用于区分同步组（两端需一致）")
        self.account_edit.setToolTip(
            "登录服务器后自动改用「服务器地址 + 用户名」作为同步账户；\n"
            "本字段仅在未配置服务器账号时生效，用于避免同局域网不同用户的数据混在一起。\n"
            "点「生成 UUID」得到随机账户，复制到其他设备填写即可组成私有同步组，\n"
            "无需注册服务器账号（UUID 仅是组名，口令仍是必填的共享凭据）。"
        )
        form.addWidget(self.account_edit, 2, 1, 1, 2)
        self.gen_account_button = push_button("生成 UUID")
        self.gen_account_button.clicked.connect(self._confirm_generate_account_uuid)
        self.copy_account_button = push_button("复制")
        self.copy_account_button.clicked.connect(self._copy_account)
        form.addWidget(self.gen_account_button, 2, 3)
        form.addWidget(self.copy_account_button, 2, 4)
        root.addLayout(form)

        self.account_state = QLabel()
        self.account_state.setObjectName("SubText")
        self.account_state.setWordWrap(True)
        root.addWidget(self.account_state)
        self.refresh_account_state()

        discover_row = QHBoxLayout()
        self.discovery_check = QCheckBox("局域网自动发现")
        self.discovery_check.setChecked(settings.discovery_enabled)
        self.discovery_check.toggled.connect(self._on_discovery_toggled)
        self.discovery_check.setToolTip("被防火墙阻止时可关闭自动发现，改为手动添加设备地址")
        self.auto_check = QCheckBox("自动同步")
        self.auto_check.setChecked(settings.auto_sync)
        self.auto_check.setToolTip("适用于 P2P 与服务器同步；任一通道可用时按间隔自动同步")
        self.interval_spin = QSpinBox()
        self.interval_spin.setRange(1, 1440)
        self.interval_spin.setSuffix(" 分钟")
        self.interval_spin.setValue(settings.sync_interval_min)
        self.auto_check.toggled.connect(self._emit_auto_sync)
        self.interval_spin.valueChanged.connect(self._emit_auto_sync)
        sync_now = push_button("立即同步", "Primary")
        sync_now.clicked.connect(self._on_sync_now)
        discover_row.addWidget(self.discovery_check)
        discover_row.addStretch(1)
        discover_row.addWidget(self.auto_check)
        discover_row.addWidget(self.interval_spin)
        discover_row.addWidget(sync_now)
        root.addLayout(discover_row)

        peers_header = QHBoxLayout()
        peers_label = QLabel("已发现/已添加的设备")
        peers_label.setObjectName("SubText")
        peers_header.addWidget(peers_label)
        peers_header.addStretch(1)
        self.peer_address_edit = QLineEdit()
        self.peer_address_edit.setPlaceholderText("手动添加：192.168.1.8:8765")
        self.peer_address_edit.setFixedWidth(200)
        self.peer_address_edit.returnPressed.connect(self._on_add_peer)
        self.add_peer_button = push_button("添加设备")
        self.add_peer_button.clicked.connect(self._on_add_peer)
        peers_header.addWidget(self.peer_address_edit)
        peers_header.addWidget(self.add_peer_button)
        root.addLayout(peers_header)

        self.peers_table = QTableWidget(0, 5)
        self.peers_table.setHorizontalHeaderLabels(["设备", "地址", "上次同步", "状态", "操作"])
        self.peers_table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.peers_table.setSelectionMode(QTableWidget.SelectionMode.NoSelection)
        self.peers_table.verticalHeader().setVisible(False)
        self.peers_table.setFixedHeight(150)
        header = self.peers_table.horizontalHeader()
        header.setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(2, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(3, QHeaderView.ResizeMode.Stretch)
        header.setSectionResizeMode(4, QHeaderView.ResizeMode.ResizeToContents)
        root.addWidget(self.peers_table)

        self.sync_status = QLabel("未同步")
        self.sync_status.setObjectName("SubText")
        self.sync_status.setWordWrap(True)
        root.addWidget(self.sync_status)

        # 总开关初始状态落到子项可用性上
        self._set_p2p_fields_enabled(self.p2p_enable_check.isChecked())

        root.addWidget(hline())
        # ------------------------------------------------------------ 服务器同步
        root.addWidget(self._section("服务器同步（经 FreeNote 服务端，可跨网络）"))
        server_form = QGridLayout()
        server_form.setHorizontalSpacing(8)
        server_form.setVerticalSpacing(8)
        server_form.addWidget(QLabel("服务器地址"), 0, 0)
        self.server_url_edit = QLineEdit(settings.server_url)
        self.server_url_edit.setPlaceholderText("http://192.168.1.8:8000 或 https://example.com")
        server_form.addWidget(self.server_url_edit, 0, 1, 1, 3)
        server_form.addWidget(QLabel("用户名"), 1, 0)
        self.server_user_edit = QLineEdit(settings.server_username)
        self.server_user_edit.setPlaceholderText("与 Android 版使用同一账号")
        server_form.addWidget(self.server_user_edit, 1, 1)
        server_form.addWidget(QLabel("密码"), 1, 2)
        self.server_password_edit = RevealOnFocusLineEdit()
        self.server_password_edit.setPlaceholderText("登录/注册时使用，不会保存")
        self.server_password_edit.returnPressed.connect(self._on_server_login)
        server_form.addWidget(self.server_password_edit, 1, 3)
        root.addLayout(server_form)

        server_row = QHBoxLayout()
        self.server_auto_check = QCheckBox("自动同步")
        self.server_auto_check.setChecked(settings.server_auto_sync)
        self.server_auto_check.setToolTip("按与 P2P 相同的间隔自动与服务端同步")
        self.server_auto_check.toggled.connect(self.server_auto_sync_changed.emit)
        self.server_tls_check = QCheckBox("跳过 HTTPS 证书校验")
        self.server_tls_check.setChecked(settings.server_tls_no_verify)
        self.server_tls_check.setToolTip(
            "仅供自签名证书的自建服务器使用：传输仍然加密，\n"
            "但无法验证服务器身份，公共网络环境下不建议开启。"
        )
        self.server_tls_check.toggled.connect(self.server_tls_no_verify_changed.emit)
        self.server_test_button = push_button("测试连接")
        self.server_test_button.clicked.connect(self._on_server_test)
        self.server_register_button = push_button("注册并登录")
        self.server_register_button.clicked.connect(self._on_server_register)
        self.server_login_button = push_button("登录", "Primary")
        self.server_login_button.clicked.connect(self._on_server_login)
        self.server_logout_button = push_button("退出登录", "Danger")
        self.server_logout_button.clicked.connect(self._on_server_logout)
        self.server_change_password_button = push_button("修改密码")
        self.server_change_password_button.clicked.connect(self._on_server_change_password)
        self.server_delete_account_button = push_button("注销账号", "Danger")
        self.server_delete_account_button.clicked.connect(self._on_server_delete_account)
        server_row.addWidget(self.server_auto_check)
        server_row.addWidget(self.server_tls_check)
        server_row.addStretch(1)
        for widget in (
            self.server_test_button,
            self.server_register_button,
            self.server_login_button,
            self.server_change_password_button,
            self.server_delete_account_button,
            self.server_logout_button,
        ):
            server_row.addWidget(widget)
        root.addLayout(server_row)

        self.server_status = QLabel("")
        self.server_status.setObjectName("SubText")
        self.server_status.setWordWrap(True)
        root.addWidget(self.server_status)
        self.refresh_server_state()

        root.addWidget(hline())
        # ------------------------------------------------------------ 备份
        root.addWidget(self._section("备份"))
        backup_row = QHBoxLayout()
        export = push_button("导出 JSON 备份")
        import_button = push_button("导入 JSON 备份")
        text_import_button = push_button("导入 Markdown / 文本 / ENEX")
        export.clicked.connect(self.export_requested.emit)
        import_button.clicked.connect(self.import_requested.emit)
        text_import_button.clicked.connect(self.text_import_requested.emit)
        backup_row.addWidget(export)
        backup_row.addWidget(import_button)
        backup_row.addWidget(text_import_button)
        backup_row.addStretch(1)
        root.addLayout(backup_row)
        hint = QLabel("导入 Markdown / 文本 / Evernote：每个 .md/.txt 成为一条便签（首个 # 标题作便签标题）；.enex 按内部便签展开（附件不入库）；也可直接拖拽文件到主窗口")
        hint.setObjectName("SubText")
        hint.setWordWrap(True)
        root.addWidget(hint)

        # -------------------------------------------------- 自动备份 / WebDAV
        auto_row = QHBoxLayout()
        self.auto_backup_check = QCheckBox("自动备份")
        self.auto_backup_check.setChecked(self.settings.auto_backup_enabled)
        self.auto_backup_check.toggled.connect(self._on_auto_backup_toggled)
        self.auto_backup_hours = QSpinBox()
        self.auto_backup_hours.setRange(1, 720)
        self.auto_backup_hours.setValue(self.settings.auto_backup_interval_hours)
        self.auto_backup_hours.setSuffix(" 小时")
        self.auto_backup_hours.valueChanged.connect(self._on_auto_backup_hours)
        self.auto_backup_dir_button = push_button("备份目录…")
        self.auto_backup_dir_button.clicked.connect(self._on_pick_backup_dir)
        self.auto_backup_dir_label = QLabel(self.settings.auto_backup_dir or "（未设置）")
        self.auto_backup_dir_label.setObjectName("SubText")
        auto_row.addWidget(self.auto_backup_check)
        auto_row.addWidget(self.auto_backup_hours)
        auto_row.addWidget(self.auto_backup_dir_button)
        auto_row.addWidget(self.auto_backup_dir_label, 1)
        root.addLayout(auto_row)

        webdav_row = QHBoxLayout()
        self.webdav_url_edit = QLineEdit(self.settings.webdav_url)
        self.webdav_url_edit.setPlaceholderText("WebDAV 地址（可选）：https://dav.example.com/freenote")
        self.webdav_user_edit = QLineEdit(self.settings.webdav_user)
        self.webdav_user_edit.setPlaceholderText("用户名")
        self.webdav_password_edit = QLineEdit(self.settings.webdav_password)
        self.webdav_password_edit.setPlaceholderText("密码")
        self.webdav_password_edit.setEchoMode(QLineEdit.EchoMode.Password)
        for widget in (self.webdav_url_edit, self.webdav_user_edit, self.webdav_password_edit):
            webdav_row.addWidget(widget, 1)
        root.addLayout(webdav_row)
        webdav_hint = QLabel("自动备份会按间隔把全量 JSON（含图片）写入备份目录并只保留最近 10 份；配置 WebDAV 后会同步上传。")
        webdav_hint.setObjectName("SubText")
        webdav_hint.setWordWrap(True)
        root.addWidget(webdav_hint)

        root.addWidget(hline())
        about = QLabel(f"FreeNote v{app_version} · 点对点直连同步 · 数据格式与 Android 版兼容")
        about.setObjectName("SubText")
        root.addWidget(about)

        scroll.setWidget(content)
        outer.addWidget(scroll)

        close_row = QHBoxLayout()
        hint = QLabel("文本框修改在点「保存并关闭」时生效；Esc / 右上角 × 放弃未保存的修改")
        hint.setObjectName("SubText")
        close_row.addWidget(hint)
        close_row.addStretch(1)
        close = push_button("保存并关闭", "Primary")
        close.clicked.connect(self.accept)
        close_row.addWidget(close)
        outer.addLayout(close_row)

        # 对话框高度不超过屏幕可用区域，超出部分由滚动区承载
        screen = self.screen() or QApplication.primaryScreen()
        if screen is not None:
            self.setMaximumHeight(max(420, screen.availableGeometry().height() - 48))

    # ------------------------------------------------------------------ 小工具

    def _section(self, text: str) -> QLabel:
        label = QLabel(text)
        label.setObjectName("SectionTitle")
        return label

    def _on_pin_action(self) -> None:
        if self.settings.pin_hash:
            self.change_pin_requested.emit()
        else:
            self.set_pin_requested.emit()

    def refresh_pin_state(self) -> None:
        if self.settings.pin_hash:
            self.pin_label.setText(f"应用锁 PIN：已设置（{self._locked_count} 条加密便签）")
            self.pin_action.setText("修改 PIN")
            self.clear_pin_button.setVisible(True)
        else:
            self.pin_label.setText("应用锁 PIN：未设置")
            self.pin_action.setText("设置 PIN")
            self.clear_pin_button.setVisible(False)

    def set_locked_count(self, count: int) -> None:
        self._locked_count = count
        self.refresh_pin_state()

    def set_sync_status(self, text: str) -> None:
        self.sync_status.setText(text)

    # ------------------------------------------------------------------ P2P 字段

    def _apply_fields(self, *_args) -> None:
        """把界面字段写入设置；有变化时通知主窗口。"""
        changed = False
        name = self.device_name_edit.text().strip()
        if name and name != self.settings.device_name:
            self.settings.device_name = name
            changed = True
        passphrase = self.passphrase_edit.text().strip()
        if passphrase != self.settings.group_passphrase:
            self.settings.group_passphrase = passphrase
            changed = True
        account = self.account_edit.text().strip()
        if account != self.settings.p2p_account:
            self.settings.p2p_account = account
            changed = True
        port = int(self.port_spin.value())
        if port != self.settings.sync_port:
            self.settings.sync_port = port
            changed = True
        p2p_enabled = self.p2p_enable_check.isChecked()
        if p2p_enabled != self.settings.p2p_enabled:
            self.settings.p2p_enabled = p2p_enabled
            changed = True
        discovery = self.discovery_check.isChecked()
        if discovery != self.settings.discovery_enabled:
            self.settings.discovery_enabled = discovery
            changed = True
        if changed:
            self._changed_any = True
            self.refresh_account_state()
            self.p2p_settings_changed.emit()
        return changed

    def current_account_text(self) -> str:
        if self.settings.server_url and self.settings.server_username:
            return (
                f"同步账户：服务器账号 {self.settings.server_username}"
                f" @ {self.settings.server_url}"
            )
        if self.settings.p2p_account:
            return f"同步账户：离线账户 {self.settings.p2p_account}"
        return "同步账户：未设置（同口令设备即同组；建议登录服务器或填写离线账户）"

    def refresh_account_state(self) -> None:
        """显示当前生效的同步账户（服务器账号优先，其次离线账户）。"""
        self.account_state.setText(self.current_account_text())

    def _on_discovery_toggled(self, _checked: bool) -> None:
        self._apply_fields()

    def _set_p2p_fields_enabled(self, enabled: bool) -> None:
        """总开关关闭时禁用 P2P 子项，与「关闭时不监听端口、不联网」语义一致。"""
        for widget in (
            self.device_name_edit,
            self.port_spin,
            self.passphrase_edit,
            self.gen_passphrase_button,
            self.copy_passphrase_button,
            self.account_edit,
            self.gen_account_button,
            self.copy_account_button,
            self.discovery_check,
            self.peer_address_edit,
            self.add_peer_button,
            self.peers_table,
        ):
            widget.setEnabled(enabled)

    def _confirm_generate_passphrase(self) -> None:
        """生成口令是一次性替换共享凭据：先确认，避免误点导致其他设备失联。"""
        answer = QMessageBox.question(
            self,
            "生成随机口令",
            "将立即把同步口令替换为新值：其他设备必须改用新口令，否则无法再发现/同步本机。\n\n继续生成？",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        )
        if answer == QMessageBox.StandardButton.Yes:
            self._generate_passphrase()

    def _generate_passphrase(self) -> None:
        self.passphrase_edit.setText(generate_passphrase())
        self._apply_fields()
        self.sync_status.setText("已生成新口令：请在其他设备上填写相同口令")

    def _copy_passphrase(self) -> None:
        text = self.passphrase_edit.text().strip()
        if not text:
            QMessageBox.warning(self, "提示", "口令为空，无法复制")
            return
        clipboard = QApplication.clipboard()
        if clipboard is not None:
            clipboard.setText(text)
        self.sync_status.setText("同步口令已复制到剪贴板")

    def _confirm_generate_account_uuid(self) -> None:
        """更换离线账户等于切换同步组：先确认，避免误点后设备列表被重置。"""
        answer = QMessageBox.question(
            self,
            "生成 UUID 离线账户",
            "将立即把离线账户替换为新值：其他设备需要填写相同账户才能同组同步。\n\n继续生成？",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        )
        if answer == QMessageBox.StandardButton.Yes:
            self._generate_account_uuid()

    def _generate_account_uuid(self) -> None:
        self.account_edit.setText(str(uuid.uuid4()))
        self._apply_fields()
        self.sync_status.setText(
            "已生成 UUID 离线账户：复制到其他设备的「离线账户」即可同组（无需注册服务器账号）"
        )

    def _copy_account(self) -> None:
        text = self.account_edit.text().strip()
        if not text:
            QMessageBox.warning(self, "提示", "离线账户为空，无法复制")
            return
        clipboard = QApplication.clipboard()
        if clipboard is not None:
            clipboard.setText(text)
        self.sync_status.setText("离线账户已复制到剪贴板")

    def _on_sync_now(self) -> None:
        self._apply_fields()
        self.sync_now_requested.emit()

    def _on_add_peer(self) -> None:
        text = self.peer_address_edit.text().strip()
        if not text:
            QMessageBox.warning(self, "提示", "请输入设备地址，例如 192.168.1.8:8765")
            return
        self._apply_fields()
        self.add_peer_requested.emit(text)

    def add_peer_result(self, ok: bool, message: str) -> None:
        if ok:
            self.peer_address_edit.clear()
        self.set_sync_status(message)

    def _emit_auto_sync(self, *_args) -> None:
        self.auto_sync_changed.emit(self.auto_check.isChecked(), self.interval_spin.value())
        if self.auto_check.isChecked() and not (
            self.p2p_enable_check.isChecked() or self.settings.server_ready()
        ):
            self.sync_status.setText(
                "自动同步已开启，但当前没有可用通道：请填写 P2P 口令并启用，或登录服务器"
            )

    # ------------------------------------------------------------------ 服务器同步

    def _apply_server_fields(self, *_args) -> bool:
        """把服务器地址/用户名写入设置（密码不保存），有变化时通知主窗口。"""
        changed = False
        url = self.server_url_edit.text().strip().rstrip("/")
        if url != self.settings.server_url:
            self.settings.server_url = url
            changed = True
        user = self.server_user_edit.text().strip()
        if user != self.settings.server_username:
            self.settings.server_username = user
            changed = True
        if changed:
            self._changed_any = True
            self.refresh_server_state()
            self.server_fields_changed.emit()
        return changed

    def take_changes(self) -> bool:
        """关闭设置后由主窗口读取：期间是否有字段被修改（用于「设置已保存」提示）。"""
        changed, self._changed_any = self._changed_any, False
        return changed

    def _server_credentials(self) -> tuple[str, str, str] | None:
        self._apply_server_fields()
        url = self.server_url_edit.text().strip()
        if not url:
            QMessageBox.warning(self, "提示", "请填写服务器地址，例如 http://192.168.1.8:8000")
            return None
        return url, self.server_user_edit.text().strip(), self.server_password_edit.text()

    def _on_server_test(self) -> None:
        if not self.server_url_edit.text().strip():
            QMessageBox.warning(self, "提示", "请填写服务器地址，例如 http://192.168.1.8:8000")
            return
        self._apply_server_fields()
        self.set_server_status("正在连接服务器…")
        self.server_test_requested.emit(self.server_url_edit.text().strip())

    def _on_server_register(self) -> None:
        credentials = self._server_credentials()
        if credentials is None:
            return
        url, username, password = credentials
        if len(username) < 2 or len(password) < 6:
            QMessageBox.warning(self, "提示", "用户名至少 2 位、密码至少 6 位")
            return
        self.set_server_status("正在注册…")
        self.server_register_requested.emit(url, username, password)

    def _on_server_login(self) -> None:
        credentials = self._server_credentials()
        if credentials is None:
            return
        url, username, password = credentials
        if not username or not password:
            QMessageBox.warning(self, "提示", "请填写用户名和密码")
            return
        self.set_server_status("正在登录…")
        self.server_login_requested.emit(url, username, password)

    def _on_server_logout(self) -> None:
        self.server_logout_requested.emit()

    def _on_server_change_password(self) -> None:
        """修改服务器密码：输入旧密码与新密码（两次确认）后提交。"""
        if not self.settings.server_ready():
            QMessageBox.warning(self, "提示", "请先登录服务器账号")
            return
        old_password, ok = QInputDialog.getText(
            self, "修改密码", "旧密码", QLineEdit.EchoMode.Password
        )
        if not ok or not old_password:
            return
        new_password, ok = QInputDialog.getText(
            self, "修改密码", "新密码（至少 6 位）", QLineEdit.EchoMode.Password
        )
        if not ok or len(new_password) < 6:
            if ok:
                QMessageBox.warning(self, "提示", "新密码至少 6 位")
            return
        confirm, ok = QInputDialog.getText(
            self, "修改密码", "再次输入新密码", QLineEdit.EchoMode.Password
        )
        if not ok:
            return
        if confirm != new_password:
            QMessageBox.warning(self, "提示", "两次输入的新密码不一致")
            return
        self.set_server_status("正在修改密码…")
        self.server_change_password_requested.emit(
            self.settings.server_url, old_password, new_password
        )

    def _on_server_delete_account(self) -> None:
        """注销服务器账号：二次确认 + 密码验证，删除服务端全部数据。"""
        if not self.settings.server_ready():
            QMessageBox.warning(self, "提示", "请先登录服务器账号")
            return
        answer = QMessageBox.warning(
            self,
            "注销账号",
            "注销后将删除服务端上的全部便签与图片，且无法恢复。\n"
            "本机便签数据会保留，可继续本地使用或通过 P2P 同步。\n\n确定要继续吗？",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        password, ok = QInputDialog.getText(
            self, "注销账号", "输入账号密码以确认注销", QLineEdit.EchoMode.Password
        )
        if not ok or not password:
            return
        self.set_server_status("正在注销账号…")
        self.server_delete_account_requested.emit(self.settings.server_url, password)

    def set_server_status(self, text: str) -> None:
        self.server_status.setText(text)

    def clear_server_password(self) -> None:
        self.server_password_edit.clear()

    # ------------------------------------------------------------------ 自动备份

    def _on_auto_backup_toggled(self, checked: bool) -> None:
        if checked and not self.settings.auto_backup_dir:
            self.auto_backup_check.setChecked(False)
            QMessageBox.information(self, "自动备份", "请先选择备份目录，再开启自动备份。")
            return
        self.settings.auto_backup_enabled = checked
        self.auto_backup_changed.emit()

    def _on_auto_backup_hours(self, value: int) -> None:
        self.settings.auto_backup_interval_hours = int(value)
        self.auto_backup_changed.emit()

    def _on_pick_backup_dir(self) -> None:
        directory = QFileDialog.getExistingDirectory(
            self, "选择自动备份目录", self.settings.auto_backup_dir or ""
        )
        if not directory:
            return
        self.settings.auto_backup_dir = directory
        self.auto_backup_dir_label.setText(directory)
        self.auto_backup_changed.emit()

    def _save_webdav(self) -> None:
        self.settings.webdav_url = self.webdav_url_edit.text()
        self.settings.webdav_user = self.webdav_user_edit.text()
        self.settings.webdav_password = self.webdav_password_edit.text()

    def set_server_busy(self, busy: bool) -> None:
        for button in (
            self.server_test_button,
            self.server_register_button,
            self.server_login_button,
            self.server_change_password_button,
            self.server_delete_account_button,
            self.server_logout_button,
        ):
            button.setEnabled(not busy)

    def refresh_server_state(self) -> None:
        """显示当前登录状态并切换退出登录按钮的可用性。"""
        if self.settings.server_ready():
            user = self.settings.server_username or "已登录"
            self.server_status.setText(f"已登录：{user} @ {self.settings.server_url}")
            self.server_logout_button.setEnabled(True)
            self.server_change_password_button.setEnabled(True)
            self.server_delete_account_button.setEnabled(True)
            self.server_login_button.setText("重新登录")
        else:
            self.server_status.setText("未登录：填写地址与账号后点「注册并登录」或「登录」")
            self.server_logout_button.setEnabled(False)
            self.server_change_password_button.setEnabled(False)
            self.server_delete_account_button.setEnabled(False)
        # 服务器账号同时决定 P2P 同步账户：登录/退出后同步刷新提示
        self.refresh_account_state()

    # ------------------------------------------------------------------ 设备列表

    def set_peers(self, peers: list) -> None:
        """peers: PeerInfo 列表（或等价 dict）。"""
        self.peers_table.setRowCount(0)
        for peer in peers:
            data = peer.to_dict() if hasattr(peer, "to_dict") else dict(peer)
            row = self.peers_table.rowCount()
            self.peers_table.insertRow(row)
            name = data.get("name") or ("手动添加" if data.get("manual") else "未知设备")
            address = f"{data.get('address') or '?'}:{data.get('port') or '?'}"
            last_sync = data.get("last_sync_at") or 0
            last_text = format_note_time(int(last_sync)) if last_sync else "—"
            self.peers_table.setItem(row, 0, QTableWidgetItem(str(name)))
            self.peers_table.setItem(row, 1, QTableWidgetItem(address))
            self.peers_table.setItem(row, 2, QTableWidgetItem(last_text))
            self.peers_table.setItem(row, 3, QTableWidgetItem(str(data.get("last_status") or "")))
            actions = QWidget()
            action_layout = QHBoxLayout(actions)
            action_layout.setContentsMargins(0, 0, 0, 0)
            action_layout.setSpacing(4)
            peer_id = str(data.get("id") or "")
            sync_button = push_button("同步")
            sync_button.clicked.connect(lambda _=False, pid=peer_id: self._request_peer_sync(pid))
            forget_button = push_button("移除", "Danger")
            forget_button.clicked.connect(lambda _=False, pid=peer_id: self._request_forget(pid))
            action_layout.addWidget(sync_button)
            action_layout.addWidget(forget_button)
            action_layout.addStretch(1)
            self.peers_table.setCellWidget(row, 4, actions)

    def _request_peer_sync(self, peer_id: str) -> None:
        if not peer_id:
            return
        self._apply_fields()
        self.sync_peer_requested.emit(peer_id)

    def _request_forget(self, peer_id: str) -> None:
        if not peer_id:
            return
        answer = QMessageBox.question(
            self,
            "移除设备",
            "从设备列表中移除该设备？（之后可重新发现或手动添加）",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        self.forget_peer_requested.emit(peer_id)

    # ------------------------------------------------------------------ 关闭

    def _pending_text_changes(self) -> bool:
        """文本框里是否有尚未保存的修改（与 _apply_* 的规范化规则保持一致）。"""
        return (
            self.device_name_edit.text().strip() != self.settings.device_name
            or self.passphrase_edit.text().strip() != self.settings.group_passphrase
            or self.account_edit.text().strip() != self.settings.p2p_account
            or self.server_url_edit.text().strip().rstrip("/") != self.settings.server_url
            or self.server_user_edit.text().strip() != self.settings.server_username
            or self.webdav_url_edit.text().strip().rstrip("/") != self.settings.webdav_url
            or self.webdav_user_edit.text().strip() != self.settings.webdav_user
            or self.webdav_password_edit.text() != self.settings.webdav_password
        )

    def accept(self) -> None:  # noqa: D102
        self._apply_fields()
        self._apply_server_fields()
        self._save_webdav()
        super().accept()

    def reject(self) -> None:  # noqa: D102
        # Esc / 关窗 = 放弃未保存的文本修改，不写盘、不切换同步账户。
        # 之前这里把输入框直接写入设置，用户以为取消实则生效（尤其会切换同步账户命名空间）。
        if self._pending_text_changes():
            answer = QMessageBox.question(
                self,
                "放弃修改",
                "口令、服务器地址或用户名等文本框中有尚未保存的修改。\n放弃这些修改并关闭？",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.No,
            )
            if answer != QMessageBox.StandardButton.Yes:
                return
        super().reject()
