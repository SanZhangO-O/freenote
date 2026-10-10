"""便签编辑页：标题/正文/清单/Markdown 预览/标签/颜色/提醒/图片/锁定。"""

from __future__ import annotations

import shutil
from pathlib import Path

from PySide6.QtCore import QTimer, Signal
from PySide6.QtGui import QDesktopServices, QTextCursor, QTextDocument
from PySide6.QtPrintSupport import QPrintDialog, QPrinter
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QDialog,
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QInputDialog,
    QLabel,
    QLineEdit,
    QMenu,
    QMessageBox,
    QPlainTextEdit,
    QScrollArea,
    QStackedWidget,
    QTextBrowser,
    QVBoxLayout,
    QWidget,
)

from .. import attachments, config, crypto, markdown as markdown_mod
from ..database import NoteStore
from ..models import (
    DEFAULT_COLORS,
    RECURRENCE_CHOICES,
    ChecklistItem,
    Note,
    attachment_token,
    folder_ancestors,
    folder_sort_key,
    format_full_time,
    image_token_path,
    new_id,
    normalize_folder,
    now_ms,
    parse_checklist,
    serialize_checklist,
    strip_image_tokens,
)
from ..session import UnlockSession
from ..settings import Settings
from .dialogs import ColorPickerDialog, HistoryDialog, PinDialog, ReminderDialog
from .widgets import clear_layout, tool_button


class NeedsPinError(Exception):
    """保存锁定便签时会话 PIN 缺失或过期。"""


# 文件夹下拉框里的“新建文件夹…”哨兵值（真实路径不会包含 \x00）
NEW_FOLDER_MARKER = "\x00new-folder"

# 便签模板（「插入模板」菜单使用；与 Android 端 NOTE_TEMPLATES 保持一致）
NOTE_TEMPLATES: tuple[tuple[str, str], ...] = (
    ("会议记录", "# 会议记录\n\n- 时间：\n- 参与人：\n\n## 议题\n\n1. \n\n## 结论\n\n- "),
    ("任务清单", "- [ ] 任务一\n- [ ] 任务二\n- [ ] 任务三"),
    ("日记", "# 日记\n\n## 今天发生了什么\n\n\n## 感受\n\n"),
    ("每周总结", "# 周总结\n\n## 本周完成\n\n- \n\n## 下周计划\n\n- "),
)


# ---------------------------------------------------------------------- 清单


class ChecklistRow(QWidget):
    changed = Signal()
    removed = Signal()

    def __init__(self, item: ChecklistItem, parent: QWidget | None = None):
        super().__init__(parent)
        self.item = item
        layout = QHBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(6)
        self.check = QCheckBox()
        self.check.setChecked(item.checked)
        self.edit = QLineEdit(item.text)
        remove = tool_button("✕", "删除该项")
        if image_token_path(item.text) is not None:
            # 正文内联图片：清单模式下原样保留，不允许勾选/编辑/删除
            self.check.setEnabled(False)
            self.edit.setReadOnly(True)
            self.edit.setText("🖼 图片（请在文本模式中编辑）")
            self.edit.setToolTip(item.text)
            remove.setVisible(False)
        else:
            self.edit.setPlaceholderText("清单项")
        layout.addWidget(self.check)
        layout.addWidget(self.edit, 1)
        layout.addWidget(remove)
        self.check.toggled.connect(self._on_check)
        self.edit.textEdited.connect(self._on_text)
        remove.clicked.connect(self.removed.emit)

    def _on_check(self, checked: bool) -> None:
        self.item.checked = checked
        self.changed.emit()

    def _on_text(self, text: str) -> None:
        self.item.text = text
        self.changed.emit()


class ChecklistEditor(QScrollArea):
    changed = Signal()

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.setWidgetResizable(True)
        self.setFrameShape(QFrame.Shape.NoFrame)
        self._container = QWidget()
        self.setWidget(self._container)
        self._layout = QVBoxLayout(self._container)
        self._layout.setContentsMargins(0, 0, 0, 0)
        self._layout.setSpacing(4)
        self._items: list[ChecklistItem] = [ChecklistItem()]
        self._rows: list[ChecklistRow] = []
        self._loading = False
        self._build()

    def _build(self) -> None:
        self._loading = True
        clear_layout(self._layout)
        self._rows.clear()
        for item in self._items:
            row = ChecklistRow(item)
            row.changed.connect(self._on_row_changed)
            row.removed.connect(lambda r=row: self._remove_row(r))
            self._layout.addWidget(row)
            self._rows.append(row)

        add_row = QWidget()
        add_layout = QHBoxLayout(add_row)
        add_layout.setContentsMargins(26, 0, 0, 0)
        add_layout.setSpacing(6)
        self._add_edit = QLineEdit()
        self._add_edit.setPlaceholderText("添加新项，回车确认")
        add_button = tool_button("＋", "添加清单项")
        add_button.clicked.connect(self._add_item)
        self._add_edit.returnPressed.connect(self._add_item)
        add_layout.addWidget(self._add_edit, 1)
        add_layout.addWidget(add_button)
        self._layout.addWidget(add_row)
        self._layout.addStretch(1)
        self._loading = False

    def set_content(self, text: str) -> None:
        self._items = parse_checklist(text)
        self._build()

    def content(self) -> str:
        if all(not item.text.strip() for item in self._items):
            return ""
        return serialize_checklist(self._items)

    def _on_row_changed(self) -> None:
        if not self._loading:
            self.changed.emit()

    def _remove_row(self, row: ChecklistRow) -> None:
        if row in self._rows:
            self._rows.remove(row)
            self._items.remove(row.item)
        if not self._items:
            self._items = [ChecklistItem()]
        self._build()
        self.changed.emit()

    def _add_item(self) -> None:
        text = self._add_edit.text().strip()
        if not text:
            return
        if self._items and not self._items[-1].text.strip() and not self._items[-1].checked:
            self._items[-1] = ChecklistItem(False, text)
        else:
            self._items.append(ChecklistItem(False, text))
        self._build()
        self._add_edit.setFocus()
        self.changed.emit()


# ---------------------------------------------------------------------- 编辑页


class EditorPage(QWidget):
    back_requested = Signal()
    note_saved = Signal(str)
    note_deleted = Signal(str)
    status_message = Signal(str)
    wiki_link_requested = Signal(str)  # 预览中点击 [[标题]]：请求打开同名便签

    def __init__(self, store: NoteStore, settings: Settings, session: UnlockSession, parent: QWidget | None = None):
        super().__init__(parent)
        self.store = store
        self.settings = settings
        self._session = session
        self._theme = "light"
        self._note_id: str | None = None
        self._created_at = now_ms()
        self._color = DEFAULT_COLORS[0]
        self._is_pinned = False
        self._is_locked = False
        self._is_checklist = False
        self._reminder: int | None = None
        self._recurrence = ""
        self._folder = ""
        self._dirty = False
        self._loading = False
        self._needs_unlock = False
        self._build_ui()
        self._autosave = QTimer(self)
        self._autosave.setSingleShot(True)
        self._autosave.timeout.connect(self._autosave_now)

    # ------------------------------------------------------------------ 构建

    def _build_ui(self) -> None:
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        bar = QWidget()
        bar.setObjectName("EditorBar")
        bar_layout = QHBoxLayout(bar)
        bar_layout.setContentsMargins(10, 8, 10, 8)
        bar_layout.setSpacing(4)
        self.back_button = tool_button("←", "返回（自动保存）")
        self.title_label = QLabel("编辑便签")
        self.title_label.setObjectName("PageTitle")
        self.pin_button = tool_button("📌", "置顶", checkable=True)
        self.checklist_button = tool_button("☑", "清单模式", checkable=True)
        self.preview_button = tool_button("👁", "Markdown 预览", checkable=True)
        self.color_button = tool_button("🎨", "颜色")
        self.reminder_button = tool_button("🔔", "提醒")
        self.image_button = tool_button("🖼", "添加图片")
        self.share_button = tool_button("📤", "导出 / 打印")
        self.lock_button = tool_button("🔒", "锁定便签（加密）", checkable=True)
        self.delete_button = tool_button("🗑", "移到回收站")
        for widget in (self.back_button, self.title_label):
            bar_layout.addWidget(widget)
        bar_layout.addStretch(1)
        for widget in (
            self.pin_button,
            self.checklist_button,
            self.preview_button,
            self.color_button,
            self.reminder_button,
            self.image_button,
            self.share_button,
            self.lock_button,
            self.delete_button,
        ):
            bar_layout.addWidget(widget)
        root.addWidget(bar)

        body = QWidget()
        body_layout = QVBoxLayout(body)
        body_layout.setContentsMargins(18, 14, 18, 12)
        body_layout.setSpacing(10)

        self.title_edit = QLineEdit()
        self.title_edit.setPlaceholderText("标题")
        self.title_edit.setStyleSheet("font-size: 17px; font-weight: 600; padding: 7px 10px;")
        self.tag_edit = QLineEdit()
        self.tag_edit.setPlaceholderText("标签（逗号分隔，如：工作, 想法）")
        self.folder_combo = QComboBox()
        self.folder_combo.setToolTip("文件夹（支持多级，如：工作/项目A）")
        self.folder_combo.setMinimumWidth(200)

        self.stack = QStackedWidget()
        self.plain_edit = QPlainTextEdit()
        self.plain_edit.setPlaceholderText("写点什么…（支持 Markdown 语法）")
        self.plain_edit.setTabChangesFocus(True)
        self.checklist_editor = ChecklistEditor()
        self.preview = QTextBrowser()
        self.preview.setOpenExternalLinks(False)  # 由 anchorClicked 统一处理（含 wiki 链接）
        self.preview.anchorClicked.connect(self._on_anchor_clicked)
        self.stack.addWidget(self.plain_edit)      # 0
        self.stack.addWidget(self.checklist_editor)  # 1
        self.stack.addWidget(self.preview)         # 2

        folder_row = QHBoxLayout()
        folder_row.setSpacing(6)
        folder_row.addWidget(QLabel("📁"))
        folder_row.addWidget(self.folder_combo, 1)

        status_row = QHBoxLayout()
        self.word_count_label = QLabel("")
        self.word_count_label.setObjectName("SubText")
        status_row.addWidget(self.word_count_label)
        status_row.addStretch(1)
        self.status_label = QLabel("")
        self.status_label.setObjectName("SubText")
        status_row.addWidget(self.status_label)

        body_layout.addWidget(self.title_edit)
        body_layout.addWidget(self.tag_edit)
        body_layout.addLayout(folder_row)
        body_layout.addWidget(self.stack, 1)
        body_layout.addLayout(status_row)
        root.addWidget(body, 1)

        self.back_button.clicked.connect(self._on_back)
        self.pin_button.toggled.connect(self._on_pin_toggled)
        self.checklist_button.toggled.connect(self._on_checklist_toggled)
        self.preview_button.toggled.connect(self._on_preview_toggled)
        self.color_button.clicked.connect(self._pick_color)
        self.reminder_button.clicked.connect(self._pick_reminder)
        self.image_button.clicked.connect(self._insert_images)
        self.share_button.clicked.connect(self._show_share_menu)
        self.lock_button.toggled.connect(self._on_lock_toggled)
        self.delete_button.clicked.connect(self._delete_note)
        self.title_edit.textEdited.connect(self._on_changed)
        self.tag_edit.textEdited.connect(self._on_changed)
        self.folder_combo.activated.connect(self._on_folder_activated)
        self.plain_edit.textChanged.connect(self._on_changed)
        self.checklist_editor.changed.connect(self._on_changed)

    # ------------------------------------------------------------------ 载入

    def set_theme(self, theme: str) -> None:
        self._theme = theme
        if self.stack.currentIndex() == 2:
            self._refresh_preview()

    def load(self, note_id: str | None, default_folder: str = "") -> str:
        """返回 ok / needs_pin / bad_pin / missing / cancelled。

        note_id 为 None 时新建便签；default_folder 作为新便签的初始文件夹
        （例如在列表页选中某文件夹后点「新建便签」）。
        """
        # 切换便签前先把挂起的编辑落库：load 会重置编辑器状态并清除脏标记，
        # 否则自动保存定时器到点后认为无改动直接返回，未保存内容被静默丢弃
        if self._dirty and not self._loading:
            try:
                self.save()
            except NeedsPinError:
                if not self._request_reauth("会话已过期，请输入 PIN 保存锁定便签"):
                    return "cancelled"
            except Exception as exc:  # noqa: BLE001
                self.status_message.emit(f"保存失败：{exc}")
                return "cancelled"
        self._loading = True
        self._note_id = None
        self._created_at = now_ms()
        self._color = DEFAULT_COLORS[0]
        self._is_pinned = False
        self._is_locked = False
        self._is_checklist = False
        self._reminder = None
        self._recurrence = ""
        self._folder = ""
        self._needs_unlock = False
        self.title_edit.clear()
        self.tag_edit.clear()
        self.plain_edit.setPlainText("")
        self.checklist_editor.set_content("")
        self._apply_mode()

        if note_id is None:
            self._folder = normalize_folder(default_folder)
            self._rebuild_folder_combo(self._folder)
            self.title_label.setText("新建便签")
            self._loading = False
            self._set_dirty(False)
            self._update_word_count()
            self._update_status("新便签")
            return "ok"

        note = self.store.get(note_id)
        if note is None:
            self._loading = False
            return "missing"

        self._note_id = note.id
        self._created_at = note.created_at
        self._color = note.color
        self._is_pinned = note.is_pinned
        self._is_locked = note.is_locked
        self._is_checklist = note.is_checklist
        self._reminder = note.reminder_time
        self._recurrence = note.recurrence
        self._folder = note.folder
        title, content = note.title, note.content
        if note.is_locked:
            pin = self._session.get_pin()
            if pin is None:
                self._loading = False
                self._needs_unlock = True
                return "needs_pin"
            title = crypto.decrypt(note.title, pin)
            content = crypto.decrypt(note.content, pin)
            if title is None or content is None:
                self._loading = False
                self._needs_unlock = True
                return "bad_pin"
        self.title_edit.setText(title)
        self.tag_edit.setText(", ".join(note.tag_list()))
        self._rebuild_folder_combo(self._folder)
        self.plain_edit.setPlainText(content)
        # 清单控件按需构建：全文逐行创建 QCheckBox+QLineEdit 的开销只应在便签
        # 确实是清单模式（或用户主动切换）时付出，否则长文本便签打开即卡住
        if self._is_checklist:
            self.checklist_editor.set_content(content)
        self.title_label.setText("编辑便签")
        self._sync_buttons()
        self._apply_mode()
        self._loading = False
        self._set_dirty(False)
        self._update_word_count()
        self._update_status("已保存")
        return "ok"

    def _sync_buttons(self) -> None:
        self.pin_button.setChecked(self._is_pinned)
        self.lock_button.setChecked(self._is_locked)
        self.checklist_button.setChecked(self._is_checklist)
        self.preview_button.setChecked(False)
        self.reminder_button.setText("🔔")
        if self._reminder is None:
            tooltip = "设置提醒"
        else:
            rule_text = dict(RECURRENCE_CHOICES).get(self._recurrence, "")
            suffix = f"（{rule_text}）" if rule_text and self._recurrence else ""
            tooltip = f"提醒：{format_full_time(self._reminder)}{suffix}"
        self.reminder_button.setToolTip(tooltip)
        self._update_lock_tooltip()

    def _update_lock_tooltip(self) -> None:
        self.lock_button.setToolTip("解除锁定" if self._is_locked else "锁定便签（加密）")

    # ------------------------------------------------------------------ 文件夹

    def _folder_paths(self) -> list[str]:
        """便签派生的路径 + 本机新建的空文件夹 + 当前便签路径，全部含隐式父级。"""
        folders: list[str] = []
        for folder in (*self.store.all_folders(), *self.store.local_folders(), self._folder):
            for path in folder_ancestors(folder):
                if path not in folders:
                    folders.append(path)
        return sorted(folders, key=folder_sort_key)

    def _rebuild_folder_combo(self, selected: str) -> None:
        was_loading = self._loading
        self._loading = True
        self.folder_combo.clear()
        self.folder_combo.addItem("（未分类）", "")
        for path in self._folder_paths():
            self.folder_combo.addItem(f"📁 {path}", path)
        self.folder_combo.insertSeparator(self.folder_combo.count())
        self.folder_combo.addItem("＋ 新建文件夹…", NEW_FOLDER_MARKER)
        self._select_folder_item(selected)
        self._loading = was_loading

    def _select_folder_item(self, folder: str) -> None:
        index = self.folder_combo.findData(folder)
        if index < 0 and folder:
            index = self.folder_combo.count() - 2
            self.folder_combo.insertItem(index, f"📁 {folder}", folder)
        if index >= 0:
            self.folder_combo.setCurrentIndex(index)

    def _on_folder_activated(self, index: int) -> None:
        if self._loading:
            return
        data = self.folder_combo.itemData(index)
        if data == NEW_FOLDER_MARKER:
            self._create_folder_dialog()
            return
        folder = str(data or "")
        if folder != self._folder:
            self._folder = folder
            self._on_changed()

    def _create_folder_dialog(self) -> None:
        self._select_folder_item(self._folder)
        text, ok = QInputDialog.getText(
            self, "新建文件夹", "文件夹路径（用 / 分隔多级，如：工作/项目A）："
        )
        if not ok:
            return
        folder = normalize_folder(text)
        if not folder:
            return
        # 显式新建即记录本地占位（即使当前只是便签路径的隐式父级），与列表页/Android 端一致
        local = self.store.local_folders()
        if folder not in local:
            local.append(folder)
            self.store.set_local_folders(local)
        self._folder = folder
        self._rebuild_folder_combo(folder)
        self._on_changed()

    # ------------------------------------------------------------------ 模式

    def _apply_mode(self) -> None:
        self.preview_button.setChecked(False)
        self.stack.setCurrentIndex(1 if self._is_checklist else 0)
        self.image_button.setEnabled(not self._is_checklist)

    def _current_content(self) -> str:
        # 按便签模式取内容而不是按当前显示的页：清单便签在 Markdown 预览页
        # 保存时若取 plain_edit 会写回过期的文本，丢掉清单里的最新勾选/编辑
        if self._is_checklist:
            return self.checklist_editor.content()
        return self.plain_edit.toPlainText()

    def _refresh_preview(self) -> None:
        content = attachments.resolve_for_render(self._current_content())
        self.preview.setHtml(markdown_mod.render_document(content, self._theme))

    def _on_preview_toggled(self, checked: bool) -> None:
        if self._loading:
            return
        if checked:
            self._refresh_preview()
            self.stack.setCurrentIndex(2)
            self.image_button.setEnabled(False)
        else:
            self._apply_mode()

    def _on_checklist_toggled(self, checked: bool) -> None:
        if self._loading:
            return
        self._is_checklist = checked
        if checked:
            self.checklist_editor.set_content(self.plain_edit.toPlainText())
            self.preview_button.setChecked(False)
            self.stack.setCurrentIndex(1)
        else:
            self.plain_edit.setPlainText(self.checklist_editor.content())
            self.preview_button.setChecked(False)
            self.stack.setCurrentIndex(0)
        self.image_button.setEnabled(not checked)
        self._on_changed()

    # ------------------------------------------------------------------ 工具栏动作

    def _insert_images(self) -> None:
        """把所选图片复制到附件目录（按内容命名），并在光标处插入便携图片标记。"""
        files, _ = QFileDialog.getOpenFileNames(
            self, "选择图片", "", "图片 (*.png *.jpg *.jpeg *.gif *.webp *.bmp)"
        )
        if not files:
            return
        tokens: list[str] = []
        for file_name in files:
            source = Path(file_name)
            try:
                name = attachments.name_for_file(source)
                target = attachments.local_path(name)
                if not target.exists():
                    shutil.copy2(source, target)
            except (OSError, ValueError):
                continue
            tokens.append(attachment_token(name))
        if not tokens:
            return
        text = self.plain_edit.toPlainText()
        cursor = self.plain_edit.textCursor()
        position = cursor.position()
        insert = "\n".join(tokens)
        if position > 0 and not text[:position].endswith("\n"):
            insert = "\n" + insert
        if position < len(text) and not text[position:].startswith("\n"):
            insert += "\n"
        cursor.insertText(insert)
        self.plain_edit.setTextCursor(cursor)
        self._on_changed()

    # ------------------------------------------------------------------ 导出 / 打印

    def _safe_filename(self, title: str) -> str:
        cleaned = "".join(ch for ch in (title or "") if ch not in '\\/:*?"<>|').strip().strip(".")
        return cleaned[:60] or "便签"

    def _export_payload(self) -> tuple[str, str]:
        return self.title_edit.text().strip() or "无标题便签", self._current_content()

    def _show_share_menu(self) -> None:
        menu = QMenu(self)
        menu.addAction("导出为 Markdown (.md)…", lambda: self._export_file("md"))
        menu.addAction("导出为 HTML (.html)…", lambda: self._export_file("html"))
        menu.addAction("导出为纯文本 (.txt)…", lambda: self._export_file("txt"))
        menu.addSeparator()
        menu.addAction("导出为 PDF…", self._export_pdf)
        menu.addAction("打印…", self._print_note)
        menu.addSeparator()
        template_menu = menu.addMenu("插入模板")
        for name, body in NOTE_TEMPLATES:
            template_menu.addAction(name, lambda _=False, text=body: self._insert_template(text))
        menu.addAction("历史版本…", self._show_history)
        menu.addSeparator()
        menu.addAction("复制全文", self._copy_all)
        menu.exec(self.share_button.mapToGlobal(self.share_button.rect().bottomLeft()))

    def _export_file(self, kind: str) -> None:
        title, content = self._export_payload()
        if kind == "md":
            text = f"# {title}\n\n{strip_image_tokens(content, '[图片]')}\n"
            extension, file_filter = ".md", "Markdown (*.md)"
        elif kind == "html":
            resolved = attachments.resolve_for_render(content)
            text = markdown_mod.render_document(resolved, self._theme)
            extension, file_filter = ".html", "HTML (*.html)"
        else:
            text = f"{title}\n\n{strip_image_tokens(content, '[图片]')}\n"
            extension, file_filter = ".txt", "文本 (*.txt)"
        path, _ = QFileDialog.getSaveFileName(
            self, "导出便签", self._safe_filename(title) + extension, file_filter
        )
        if not path:
            return
        if not path.lower().endswith(extension):
            path += extension
        try:
            Path(path).write_text(text, encoding="utf-8")
        except OSError as exc:
            QMessageBox.warning(self, "导出失败", f"无法写入文件：{exc}")
            return
        self.status_message.emit(f"已导出：{path}")

    def _print_html(self, printer: QPrinter) -> None:
        title, content = self._export_payload()
        resolved = attachments.resolve_for_render(content)
        document = QTextDocument()
        document.setHtml(markdown_mod.render_document(resolved, self._theme))
        document.setMetaInformation(QTextDocument.MetaInformation.DocumentTitle, title)
        document.print_(printer)

    def _export_pdf(self) -> None:
        title, _content = self._export_payload()
        path, _ = QFileDialog.getSaveFileName(
            self, "导出为 PDF", self._safe_filename(title) + ".pdf", "PDF (*.pdf)"
        )
        if not path:
            return
        if not path.lower().endswith(".pdf"):
            path += ".pdf"
        printer = QPrinter(QPrinter.PrinterMode.HighResolution)
        printer.setOutputFormat(QPrinter.OutputFormat.PdfFormat)
        printer.setOutputFileName(path)
        self._print_html(printer)
        self.status_message.emit(f"已导出 PDF：{path}")

    def _print_note(self) -> None:
        printer = QPrinter(QPrinter.PrinterMode.HighResolution)
        dialog = QPrintDialog(printer, self)
        dialog.setWindowTitle("打印便签")
        if dialog.exec() != QPrintDialog.DialogCode.Accepted:
            return
        self._print_html(printer)

    def _copy_all(self) -> None:
        title, content = self._export_payload()
        clipboard = QApplication.clipboard()
        if clipboard is not None:
            clipboard.setText(f"{title}\n\n{content}")
        self.status_message.emit("已复制全文到剪贴板")

    def _show_history(self) -> None:
        """浏览/恢复本机保存的历史版本（未保存的新便签没有历史）。"""
        if self._note_id is None:
            self.status_message.emit("新便签保存后才有历史版本")
            return
        note = self.store.get(self._note_id)
        if note is None:
            return
        dialog = HistoryDialog(self, self.store, note, self._theme, self._session.get_pin())
        if dialog.exec() == QDialog.DialogCode.Accepted and dialog.restored:
            self.load_note(self._note_id)
            self.status_message.emit("已恢复历史版本（内容已进入同步队列）")

    def _pick_color(self) -> None:
        color = ColorPickerDialog.request(self, self._color, self._theme)
        if color is not None and color != self._color:
            self._color = color
            self._on_changed()

    def _pick_reminder(self) -> None:
        action, value, recurrence = ReminderDialog.request(self, self._reminder, self._recurrence)
        if action == "cancel":
            return
        self._reminder = value if action == "set" else None
        self._recurrence = recurrence if action == "set" else ""
        self._sync_buttons()
        self._on_changed()

    def _on_pin_toggled(self, checked: bool) -> None:
        if self._loading:
            return
        self._is_pinned = checked
        self._on_changed()

    def _on_lock_toggled(self, checked: bool) -> None:
        if self._loading:
            return
        if checked:
            if not self.settings.pin_hash:
                QMessageBox.information(self, "未设置 PIN", "请先在设置中设置应用锁 PIN，才能锁定便签。")
                self._set_lock_checked(False)
                return
            if self._session.get_pin() is None:
                pin = PinDialog.request(self, self.settings, "锁定便签", "请输入应用锁 PIN，用于加密此便签")
                if pin is None:
                    self._set_lock_checked(False)
                    return
                self._session.set_pin(pin)
            self._is_locked = True
        else:
            if self._session.get_pin() is None:
                pin = PinDialog.request(self, self.settings, "解除锁定", "请输入应用锁 PIN")
                if pin is None:
                    self._set_lock_checked(True)
                    return
                self._session.set_pin(pin)
            self._is_locked = False
        self._update_lock_tooltip()
        self._on_changed()

    def _set_lock_checked(self, value: bool) -> None:
        self._loading = True
        self.lock_button.setChecked(value)
        self._loading = False

    def _delete_note(self) -> None:
        if self._note_id is None:
            if QMessageBox.question(self, "放弃便签", "放弃这条未保存的便签？") == QMessageBox.StandardButton.Yes:
                self.back_requested.emit()
            return
        if QMessageBox.question(self, "删除便签", "将便签移到回收站？（30 天后自动清除）") != QMessageBox.StandardButton.Yes:
            return
        note_id = self._note_id
        self.store.move_to_trash(note_id)
        self._note_id = None
        self._set_dirty(False)
        self.note_deleted.emit(note_id)

    # ------------------------------------------------------------------ 保存

    def _on_anchor_clicked(self, url) -> None:
        """预览中的链接：wiki 链接跳便签，http(s) 用系统浏览器打开。"""
        from urllib.parse import unquote

        target = url.toString()
        if target.startswith("freenote-note:"):
            # 优先用原始（已百分号转义）片段解码一次：url.path() 是 PrettyDecoded，
            # 标题里字面量 %2F 之类会被二次解码成错误字符
            raw = target[len("freenote-note:"):]
            title = (unquote(raw) if raw else url.path()).strip()
            if title:
                self.wiki_link_requested.emit(title)
            return
        if url.scheme() in ("http", "https"):
            QDesktopServices.openUrl(url)

    def _update_word_count(self) -> None:
        content = self._current_content()
        stripped = content.strip()
        if not stripped:
            self.word_count_label.setText("")
            return
        chars = sum(1 for ch in stripped if not ch.isspace())
        lines = stripped.count("\n") + 1
        self.word_count_label.setText(f"{chars} 字 · {lines} 行")

    def _insert_template(self, template: str) -> None:
        """在光标处插入模板；正文为空时直接替换（清单模式下不允许）。"""
        if self._is_checklist:
            self.status_message.emit("清单模式下无法插入 Markdown 模板")
            return
        current = self.plain_edit.toPlainText()
        if not current.strip():
            self.plain_edit.setPlainText(template)
        else:
            cursor = self.plain_edit.textCursor()
            cursor.movePosition(QTextCursor.MoveOperation.End)
            cursor.insertText("\n\n" + template)
            self.plain_edit.setTextCursor(cursor)
        self._on_changed()

    def _on_changed(self, *_args) -> None:
        if self._loading or self._needs_unlock:
            return
        self._set_dirty(True)
        self._update_word_count()
        self._update_status("未保存更改…")
        self._autosave.start(config.AUTO_SAVE_DELAY_MS)

    def _set_dirty(self, value: bool) -> None:
        self._dirty = value

    def _collect(self) -> Note:
        note = Note(
            id=self._note_id or new_id(),
            title=self.title_edit.text().strip(),
            content=self._current_content().strip(),
            color=self._color,
            created_at=self._created_at,
            is_pinned=self._is_pinned,
            is_locked=self._is_locked,
            is_checklist=self._is_checklist,
            reminder_time=self._reminder,
            folder=self._folder,
            recurrence=self._recurrence,
        )
        note.set_tags(self.tag_edit.text().replace("，", ",").split(","))
        return note

    def _is_blank(self, note: Note) -> bool:
        return not (
            note.title
            or note.content
            or note.tags
            or note.is_pinned
            or note.is_checklist
            or note.reminder_time
            or note.recurrence
            or note.color != DEFAULT_COLORS[0]
        )

    def save(self) -> bool:
        if self._needs_unlock:
            return False
        if not self._dirty and self._note_id is not None:
            return True
        note = self._collect()
        if self._note_id is None and self._is_blank(note):
            return False
        if note.is_locked:
            pin = self._session.get_pin()
            if pin is None:
                raise NeedsPinError()
            note.title = crypto.encrypt(note.title, pin)
            note.content = crypto.encrypt(note.content, pin)
        saved = self.store.save(note)
        self._note_id = saved.id
        self.title_label.setText("编辑便签")
        self._set_dirty(False)
        self._autosave.stop()
        self._update_status("已保存")
        self.note_saved.emit(saved.id)
        return True

    def _autosave_now(self) -> None:
        try:
            self.save()
        except NeedsPinError:
            self._request_reauth("锁定便签需要验证 PIN 才能保存")
        except Exception as exc:  # noqa: BLE001
            self.status_message.emit(f"保存失败：{exc}")

    def _request_reauth(self, prompt: str) -> bool:
        pin = PinDialog.request(self, self.settings, "验证 PIN", prompt)
        if pin is None:
            return False
        self._session.set_pin(pin)
        try:
            return self.save()
        except NeedsPinError:
            return False

    def _on_back(self) -> None:
        try:
            self.save()
        except NeedsPinError:
            if not self._request_reauth("会话已过期，请输入 PIN 保存锁定便签"):
                return
        except Exception as exc:  # noqa: BLE001
            self.status_message.emit(f"保存失败：{exc}")
            return
        self.back_requested.emit()

    def prepare_close(self) -> bool:
        """窗口关闭前保存；返回 False 表示用户取消。"""
        try:
            self.save()
        except NeedsPinError:
            return self._request_reauth("会话已过期，请输入 PIN 保存锁定便签")
        except Exception as exc:  # noqa: BLE001
            # 保存失败时窗口不关闭，必须给出明确原因（状态栏文字太容易被忽略）
            QMessageBox.warning(
                self,
                "无法关闭",
                f"保存失败：{exc}\n\n便签尚未保存，已取消关闭。请检查磁盘空间或权限后重试。",
            )
            return False
        return True

    def _update_status(self, text: str) -> None:
        from datetime import datetime

        self.status_label.setText(f"{text} · {datetime.now().strftime('%H:%M:%S')}")
