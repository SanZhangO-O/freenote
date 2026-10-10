"""主题：浅色/深色配色、QSS 样式表、便签卡片配色、应用图标。"""

from __future__ import annotations

import sys
from pathlib import Path

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import (
    QColor,
    QIcon,
    QLinearGradient,
    QPainter,
    QPainterPath,
    QPen,
    QPixmap,
)

LIGHT = {
    "bg": "#f4f3f7",
    "surface": "#ffffff",
    "surface2": "#faf9fc",
    "text": "#1d1b20",
    "subtext": "#605d66",
    "border": "#e2e0e6",
    "hover": "#eae7f0",
    "pressed": "#ded9e8",
    "input_bg": "#ffffff",
    "accent": "#6750a4",
    "accent_hover": "#7c68b8",
    "accent_soft": "#e8def8",
    "on_accent": "#ffffff",
    "danger": "#b3261e",
    "scroll": "#c9c5d0",
    "shadow": "#33000000",
}

DARK = {
    "bg": "#141218",
    "surface": "#211f26",
    "surface2": "#2b2930",
    "text": "#e7e0e8",
    "subtext": "#a8a2ad",
    "border": "#3d3a44",
    "hover": "#38343e",
    "pressed": "#454049",
    "input_bg": "#2b2930",
    "accent": "#cfbcff",
    "accent_hover": "#bda7f5",
    "accent_soft": "#4a4458",
    "on_accent": "#221a33",
    "danger": "#f2b8b5",
    "scroll": "#4a4650",
    "shadow": "#66000000",
}


def system_is_dark() -> bool:
    """Windows 上读取系统应用主题，其他平台回退到调色板亮度。"""
    try:
        import winreg

        key = winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize",
        )
        value, _ = winreg.QueryValueEx(key, "AppsUseLightTheme")
        return value == 0
    except Exception:  # noqa: BLE001
        from PySide6.QtWidgets import QApplication

        app = QApplication.instance()
        if app is not None:
            color = app.palette().window().color()
            return color.lightness() < 128
        return False


def effective_theme(mode: str) -> str:
    """mode: system/light/dark -> 实际使用 light/dark。"""
    if mode == "light":
        return "light"
    if mode == "dark":
        return "dark"
    return "dark" if system_is_dark() else "light"


def colors(theme: str) -> dict[str, str]:
    return DARK if theme == "dark" else LIGHT


def note_card_colors(color_value: int, theme: str) -> tuple[str, str, str]:
    """返回便签卡片的 (背景, 边框, 文字) 颜色；深色模式下把浅色便签压暗。"""
    base = QColor(color_value & 0xFFFFFF)
    if theme == "dark":
        bg = _mix(base, QColor(DARK["bg"]), 0.72)
        border = _mix(base, QColor(DARK["bg"]), 0.55)
        text = "rgba(255, 255, 255, 0.92)"
        return bg.name(), border.name(), text
    border = _mix(base, QColor("#000000"), 0.12)
    return base.name(), border.name(), "#1d1b20"


def _mix(color_a: QColor, color_b: QColor, ratio_b: float) -> QColor:
    ratio_a = 1.0 - ratio_b
    return QColor(
        int(color_a.red() * ratio_a + color_b.red() * ratio_b),
        int(color_a.green() * ratio_a + color_b.green() * ratio_b),
        int(color_a.blue() * ratio_a + color_b.blue() * ratio_b),
    )


# 图标几何：与 Android 端矢量前景共用同一套 108 视图坐标
_ICON_CENTER = 54.0
_ICON_VIEW = 78.0
_ICON_BG = ("#7c68b8", "#5a4691")
_ICON_PAPER = (36.0, 28.0, 72.0, 80.0, 6.0)
_ICON_FOLD = ((72.0, 68.0), (72.0, 80.0), (60.0, 80.0))
_ICON_LINES = (((45.0, 43.0), (63.0, 43.0)), ((45.0, 54.0), (63.0, 54.0)), ((45.0, 65.0), (57.0, 65.0)))


def assets_dir() -> Path:
    """资源目录：源码运行为 windows/assets，PyInstaller 打包后为 _MEIPASS/assets。"""
    base = getattr(sys, "_MEIPASS", None)
    if base:
        return Path(base) / "assets"
    return Path(__file__).resolve().parents[2] / "assets"


def make_app_icon(size: int = 64) -> QIcon:
    """应用图标：优先加载 assets/freenote.ico，缺失时回退到程序内绘制。"""
    icon_file = assets_dir() / "freenote.ico"
    if icon_file.is_file():
        icon = QIcon(str(icon_file))
        if not icon.isNull():
            return icon
    return _draw_app_icon(size)


def _draw_app_icon(size: int = 64) -> QIcon:
    """程序内绘制应用图标（紫底 + 便签纸），作为图标资源缺失时的回退。"""
    pixmap = QPixmap(size, size)
    pixmap.fill(Qt.GlobalColor.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)

    unit = size / _ICON_VIEW
    shift = size / 2 - _ICON_CENTER * unit

    def point(x: float, y: float) -> QPointF:
        return QPointF(x * unit + shift, y * unit + shift)

    gradient = QLinearGradient(QPointF(0, 0), QPointF(0, size))
    gradient.setColorAt(0.0, QColor(_ICON_BG[0]))
    gradient.setColorAt(1.0, QColor(_ICON_BG[1]))
    painter.setPen(Qt.PenStyle.NoPen)
    painter.setBrush(gradient)
    radius = size * 0.22
    painter.drawRoundedRect(QRectF(0, 0, size, size), radius, radius)

    left, top, right, bottom, paper_radius = _ICON_PAPER
    paper = QPainterPath()
    paper.addRoundedRect(
        QRectF(point(left, top), point(right, bottom)),
        paper_radius * unit,
        paper_radius * unit,
    )
    fold = QPainterPath()
    fold.moveTo(point(*_ICON_FOLD[0]))
    fold.lineTo(point(*_ICON_FOLD[1]))
    fold.lineTo(point(*_ICON_FOLD[2]))
    fold.closeSubpath()
    painter.fillPath(paper.subtracted(fold), QColor("#ffffff"))
    painter.fillPath(fold, QColor("#cfc2f0"))

    pen = QPen(QColor("#6750a4"))
    pen.setWidthF(4 * unit)
    pen.setCapStyle(Qt.PenCapStyle.RoundCap)
    painter.setPen(pen)
    for (x1, y1), (x2, y2) in _ICON_LINES:
        painter.drawLine(point(x1, y1), point(x2, y2))
    painter.end()
    return QIcon(pixmap)


def build_qss(theme: str) -> str:
    c = colors(theme)
    return f"""
    * {{
        font-family: "Microsoft YaHei UI", "Segoe UI", "PingFang SC", sans-serif;
        font-size: 13px;
        outline: none;
    }}
    QWidget {{ color: {c['text']}; }}
    QMainWindow, QDialog {{ background: {c['bg']}; }}
    QLabel {{ background: transparent; }}
    QLabel#PageTitle {{ font-size: 17px; font-weight: 600; }}
    QLabel#SubText {{ color: {c['subtext']}; font-size: 12px; }}
    QLabel#SectionTitle {{ font-size: 14px; font-weight: 600; padding-top: 4px; }}

    #TopBar {{ background: {c['surface']}; border-bottom: 1px solid {c['border']}; }}
    #EditorBar {{ background: {c['surface']}; border-bottom: 1px solid {c['border']}; }}
    #CardFrame {{ background: {c['surface']}; border: 1px solid {c['border']}; border-radius: 10px; }}

    QPushButton {{
        background: {c['surface2']};
        border: 1px solid {c['border']};
        border-radius: 6px;
        padding: 5px 14px;
    }}
    QPushButton:hover {{ background: {c['hover']}; }}
    QPushButton:pressed {{ background: {c['pressed']}; }}
    QPushButton:disabled {{ color: {c['subtext']}; }}
    QPushButton#Primary {{
        background: {c['accent']};
        color: {c['on_accent']};
        border: none;
        font-weight: 600;
    }}
    QPushButton#Primary:hover {{ background: {c['accent_hover']}; }}
    QPushButton#Danger {{ color: {c['danger']}; border: 1px solid {c['danger']}; background: transparent; }}
    QPushButton#Danger:hover {{ background: {c['hover']}; }}
    QPushButton#Chip {{
        border-radius: 13px;
        padding: 3px 12px;
        background: transparent;
        border: 1px solid {c['border']};
        color: {c['subtext']};
    }}
    QPushButton#Chip:hover {{ background: {c['hover']}; }}
    QPushButton#Chip:checked {{
        background: {c['accent_soft']};
        border-color: {c['accent']};
        color: {c['text']};
        font-weight: 600;
    }}

    QToolButton {{
        border: none;
        border-radius: 6px;
        padding: 5px;
        background: transparent;
        font-size: 15px;
    }}
    QToolButton:hover {{ background: {c['hover']}; }}
    QToolButton:checked {{ background: {c['accent_soft']}; }}

    QLineEdit, QPlainTextEdit, QTextEdit, QTextBrowser, QSpinBox, QComboBox, QDateTimeEdit {{
        background: {c['input_bg']};
        border: 1px solid {c['border']};
        border-radius: 6px;
        padding: 5px 8px;
        selection-background-color: {c['accent']};
        selection-color: {c['on_accent']};
    }}
    QLineEdit:focus, QPlainTextEdit:focus, QTextEdit:focus, QSpinBox:focus,
    QComboBox:focus, QDateTimeEdit:focus {{ border: 1px solid {c['accent']}; }}
    QComboBox::drop-down {{ border: none; width: 18px; }}
    QComboBox QAbstractItemView {{
        background: {c['surface']};
        border: 1px solid {c['border']};
        selection-background-color: {c['hover']};
        selection-color: {c['text']};
    }}

    QScrollArea {{ border: none; background: transparent; }}
    QScrollArea > QWidget > QWidget {{ background: transparent; }}
    QScrollBar:vertical {{ background: transparent; width: 10px; margin: 0; }}
    QScrollBar::handle:vertical {{ background: {c['scroll']}; border-radius: 5px; min-height: 30px; }}
    QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{ height: 0; }}
    QScrollBar:horizontal {{ background: transparent; height: 10px; margin: 0; }}
    QScrollBar::handle:horizontal {{ background: {c['scroll']}; border-radius: 5px; min-width: 30px; }}
    QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal {{ width: 0; }}

    QMenu {{ background: {c['surface']}; border: 1px solid {c['border']}; border-radius: 8px; padding: 4px; }}
    QMenu::item {{ padding: 6px 26px 6px 12px; border-radius: 5px; }}
    QMenu::item:selected {{ background: {c['hover']}; }}
    QMenu::separator {{ height: 1px; background: {c['border']}; margin: 4px 6px; }}

    QCheckBox {{ spacing: 7px; }}
    QCheckBox::indicator {{ width: 16px; height: 16px; border-radius: 4px; border: 1px solid {c['border']}; background: {c['input_bg']}; }}
    QCheckBox::indicator:checked {{ background: {c['accent']}; border-color: {c['accent']}; }}

    QRadioButton {{ spacing: 7px; }}
    QRadioButton::indicator {{ width: 15px; height: 15px; border-radius: 8px; border: 1px solid {c['border']}; background: {c['input_bg']}; }}
    QRadioButton::indicator:checked {{ background: {c['accent']}; border: 4px solid {c['input_bg']}; }}

    QTableWidget, QListWidget {{
        background: {c['surface']};
        border: 1px solid {c['border']};
        border-radius: 8px;
        gridline-color: {c['border']};
    }}
    QHeaderView::section {{
        background: {c['surface2']};
        color: {c['subtext']};
        border: none;
        border-bottom: 1px solid {c['border']};
        padding: 5px 8px;
    }}
    QTableWidget::item:selected, QListWidget::item:selected {{ background: {c['accent_soft']}; color: {c['text']}; }}

    QSplitter::handle {{ background: {c['border']}; width: 1px; }}
    QToolTip {{ background: {c['surface']}; color: {c['text']}; border: 1px solid {c['border']}; padding: 4px 6px; }}
    QStatusBar {{ background: {c['surface']}; color: {c['subtext']}; border-top: 1px solid {c['border']}; }}
    QMessageBox {{ background: {c['surface']}; }}
    """
