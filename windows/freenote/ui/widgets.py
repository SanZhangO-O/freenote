"""通用 UI 组件：流式布局、便签卡片、小工具函数。"""

from __future__ import annotations

from PySide6.QtCore import QPoint, QRect, QSize, Qt, Signal
from PySide6.QtGui import QAction, QFont, QFontMetrics
from PySide6.QtWidgets import (
    QFrame,
    QHBoxLayout,
    QLabel,
    QLayout,
    QMenu,
    QPushButton,
    QToolButton,
    QVBoxLayout,
    QWidget,
)

from ..models import Note, format_note_time, parse_checklist, strip_image_tokens
from . import theme as theme_mod


def tool_button(text: str, tooltip: str, checkable: bool = False, parent: QWidget | None = None) -> QToolButton:
    button = QToolButton(parent)
    button.setText(text)
    button.setToolTip(tooltip)
    button.setCheckable(checkable)
    button.setCursor(Qt.CursorShape.PointingHandCursor)
    return button


def push_button(text: str, object_name: str = "", parent: QWidget | None = None) -> QPushButton:
    button = QPushButton(text, parent)
    if object_name:
        button.setObjectName(object_name)
    button.setCursor(Qt.CursorShape.PointingHandCursor)
    return button


def hline(parent: QWidget | None = None) -> QFrame:
    line = QFrame(parent)
    line.setFrameShape(QFrame.Shape.HLine)
    line.setFixedHeight(1)
    line.setStyleSheet("background: palette(mid); border: none;")
    return line


def clear_layout(layout: QLayout) -> None:
    while layout.count():
        item = layout.takeAt(0)
        widget = item.widget()
        if widget is not None:
            # 先脱离父级立即停止绘制，再排队销毁；只 deleteLater() 会在事件循环处理前残留
            widget.setParent(None)
            widget.deleteLater()


class FlowLayout(QLayout):
    """自动换行的流式布局（Qt 官方示例改写，支持动态删除）。"""

    def __init__(self, parent: QWidget | None = None, margin: int = 0, spacing: int = 12):
        super().__init__(parent)
        self.setContentsMargins(margin, margin, margin, margin)
        self.setSpacing(spacing)
        self._items: list = []

    def addItem(self, item) -> None:  # noqa: N802
        self._items.append(item)

    def count(self) -> int:
        return len(self._items)

    def itemAt(self, index: int):  # noqa: N802
        if 0 <= index < len(self._items):
            return self._items[index]
        return None

    def takeAt(self, index: int):  # noqa: N802
        if 0 <= index < len(self._items):
            return self._items.pop(index)
        return None

    def expandingDirections(self):  # noqa: N802
        return Qt.Orientation(0)

    def hasHeightForWidth(self) -> bool:  # noqa: N802
        return True

    def heightForWidth(self, width: int) -> int:  # noqa: N802
        return self._do_layout(QRect(0, 0, width, 0), test_only=True)

    def setGeometry(self, rect: QRect) -> None:  # noqa: N802
        super().setGeometry(rect)
        self._do_layout(rect, test_only=False)

    def sizeHint(self) -> QSize:  # noqa: N802
        return self.minimumSize()

    def minimumSize(self) -> QSize:  # noqa: N802
        size = QSize()
        for item in self._items:
            size = size.expandedTo(item.minimumSize())
        margins = self.contentsMargins()
        size += QSize(margins.left() + margins.right(), margins.top() + margins.bottom())
        return size

    def _do_layout(self, rect: QRect, test_only: bool) -> int:
        margins = self.contentsMargins()
        effective = rect.adjusted(margins.left(), margins.top(), -margins.right(), -margins.bottom())
        x, y = effective.x(), effective.y()
        line_height = 0
        for item in self._items:
            widget = item.widget()
            if widget is not None and widget.isHidden():
                continue
            space_x = self.spacing()
            space_y = self.spacing()
            next_x = x + item.sizeHint().width() + space_x
            if next_x - space_x > effective.right() and line_height > 0:
                x = effective.x()
                y = y + line_height + space_y
                next_x = x + item.sizeHint().width() + space_x
                line_height = 0
            if not test_only:
                item.setGeometry(QRect(QPoint(x, y), item.sizeHint()))
            x = next_x
            line_height = max(line_height, item.sizeHint().height())
        return y + line_height - rect.y() + margins.bottom()


class NoteCard(QFrame):
    """便签卡片。trash_mode=True 时显示恢复/彻底删除按钮。"""

    clicked = Signal(str)
    pin_toggled = Signal(str)
    delete_requested = Signal(str)
    restore_requested = Signal(str)
    purge_requested = Signal(str)

    WIDTH = 244

    def __init__(self, note: Note, theme: str, trash_mode: bool = False, parent: QWidget | None = None):
        super().__init__(parent)
        self.note = note
        self.trash_mode = trash_mode
        self._batch_selected = False
        self.setObjectName("NoteCard")
        self.setFixedWidth(self.WIDTH)
        self.setAttribute(Qt.WidgetAttribute.WA_Hover, True)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self._apply_colors(theme)
        self._build()

    def set_batch_selected(self, selected: bool) -> None:
        """批量模式下的选中态：加粗边框高亮（再次刷新卡片时由主窗口重新设置）。"""
        self._batch_selected = bool(selected)
        self._apply_colors(self._theme)

    def _apply_colors(self, theme: str) -> None:
        self._theme = theme
        bg, border, text = theme_mod.note_card_colors(self.note.color, theme)
        if self._batch_selected:
            accent = theme_mod.colors(theme)["accent"]
            self.setStyleSheet(
                f"QFrame#NoteCard {{ background: {bg}; border: 2px solid {accent}; border-radius: 10px; }}"
                f"QLabel {{ color: {text}; background: transparent; }}"
            )
            return
        self.setStyleSheet(
            f"QFrame#NoteCard {{ background: {bg}; border: 1px solid {border}; border-radius: 10px; }}"
            f"QFrame#NoteCard:hover {{ border: 1px solid {theme_mod.colors(theme)['accent']}; }}"
            f"QLabel {{ color: {text}; background: transparent; }}"
        )

    def _build(self) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(12, 10, 12, 10)
        layout.setSpacing(6)

        header = QHBoxLayout()
        header.setSpacing(6)
        title = QLabel()
        title_font = QFont()
        title_font.setBold(True)
        title_font.setPointSize(10)
        title.setFont(title_font)
        title.setWordWrap(True)
        metrics = QFontMetrics(title.font())
        if self.note.is_locked:
            header_title = "🔒 已加密便签"
        elif self.note.title.strip():
            header_title = metrics.elidedText(self.note.title.strip(), Qt.TextElideMode.ElideRight, self.WIDTH - 60)
        else:
            header_title = "无标题"
        title.setText(header_title)
        header.addWidget(title, 1)
        if self.note.is_pinned:
            pin = QLabel("📌")
            pin.setToolTip("已置顶")
            header.addWidget(pin, 0, Qt.AlignmentFlag.AlignTop)
        layout.addLayout(header)

        preview = self._preview_text()
        if preview:
            label = QLabel(preview)
            label.setWordWrap(True)
            label.setAlignment(Qt.AlignmentFlag.AlignTop | Qt.AlignmentFlag.AlignLeft)
            label.setMaximumHeight(92)
            label.setStyleSheet("opacity: 0.85;")
            layout.addWidget(label)

        tags = self.note.tag_list()[:3]
        if tags:
            tag_label = QLabel("  ".join(f"#{t}" for t in tags))
            tag_label.setStyleSheet("font-size: 11px; opacity: 0.7;")
            layout.addWidget(tag_label)

        if self.note.folder:
            folder_font = QFont()
            folder_font.setPointSize(9)
            folder_label = QLabel()
            folder_label.setFont(folder_font)
            folder_label.setText(
                QFontMetrics(folder_font).elidedText(
                    f"📁 {self.note.folder}", Qt.TextElideMode.ElideMiddle, self.WIDTH - 28
                )
            )
            folder_label.setToolTip(self.note.folder)
            folder_label.setStyleSheet("font-size: 11px; opacity: 0.7;")
            layout.addWidget(folder_label)

        footer = QHBoxLayout()
        footer.setSpacing(6)
        time_label = QLabel(
            f"删除于 {format_note_time(self.note.deleted_at)}" if self.trash_mode and self.note.deleted_at
            else format_note_time(self.note.updated_at)
        )
        time_label.setStyleSheet("font-size: 11px; opacity: 0.65;")
        footer.addWidget(time_label)
        footer.addStretch(1)
        if self.note.reminder_time:
            reminder = QLabel("🔔")
            reminder.setToolTip("有提醒")
            footer.addWidget(reminder)
        if self.note.is_checklist:
            footer.addWidget(QLabel("☑"))
        # 廉价子串检查即可：真正的图片解析已在 _preview_text 中做过一次
        if not self.note.is_locked and "![" in self.note.content:
            footer.addWidget(QLabel("🖼"))
        layout.addLayout(footer)

        if self.trash_mode:
            actions = QHBoxLayout()
            actions.setSpacing(6)
            restore = push_button("恢复", "Primary")
            purge = push_button("彻底删除", "Danger")
            restore.clicked.connect(lambda: self.restore_requested.emit(self.note.id))
            purge.clicked.connect(lambda: self.purge_requested.emit(self.note.id))
            actions.addWidget(restore)
            actions.addWidget(purge)
            actions.addStretch(1)
            layout.addLayout(actions)

    def _preview_text(self) -> str:
        if self.note.is_locked:
            return ""
        content = strip_image_tokens(self.note.content).strip()
        if not content:
            return ""
        if self.note.is_checklist:
            items = [it for it in parse_checklist(content) if it.text.strip()]
            lines = [f"{'☑' if it.checked else '☐'} {it.text}" for it in items[:5]]
            return "\n".join(lines)
        return content[:160]

    # ------------------------------------------------------------------ 交互

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if event.button() == Qt.MouseButton.LeftButton and self.rect().contains(event.position().toPoint()):
            self.clicked.emit(self.note.id)
        super().mouseReleaseEvent(event)

    def contextMenuEvent(self, event) -> None:  # noqa: N802
        menu = QMenu(self)
        if self.trash_mode:
            restore = QAction("恢复", menu)
            purge = QAction("彻底删除", menu)
            restore.triggered.connect(lambda: self.restore_requested.emit(self.note.id))
            purge.triggered.connect(lambda: self.purge_requested.emit(self.note.id))
            menu.addAction(restore)
            menu.addAction(purge)
        else:
            pin = QAction("取消置顶" if self.note.is_pinned else "置顶", menu)
            delete = QAction("移到回收站", menu)
            pin.triggered.connect(lambda: self.pin_toggled.emit(self.note.id))
            delete.triggered.connect(lambda: self.delete_requested.emit(self.note.id))
            menu.addAction(pin)
            menu.addSeparator()
            menu.addAction(delete)
        menu.exec(event.globalPos())
