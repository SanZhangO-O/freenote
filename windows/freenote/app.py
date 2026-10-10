"""FreeNote Windows 应用入口。"""

from __future__ import annotations

import sys

from PySide6.QtCore import QLibraryInfo, QSharedMemory, QTranslator
from PySide6.QtWidgets import QApplication, QMessageBox

from . import __version__, config
from .database import NoteStore
from .p2p.engine import PeerSyncEngine
from .session import UnlockSession
from .settings import Settings
from .ui import theme as theme_mod
from .ui.dialogs import PinDialog
from .ui.main_window import MainWindow


def _acquire_single_instance(app: QApplication) -> bool:
    """QSharedMemory 单实例保护：已有实例在运行时返回 False。

    两个实例会同时监听同步端口、互相覆盖 settings.json 并写同一个 SQLite，
    因此第二次启动直接提示并退出。
    """
    guard = QSharedMemory("freenote-single-instance")
    if not guard.create(1):
        return False
    # 保持引用：共享内存段随对象析构释放，被 GC 掉会提前失去保护
    app._single_instance_guard = guard  # noqa: SLF001
    return True


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("FreeNote")
    app.setOrganizationName("FreeNote")
    app.setApplicationVersion(__version__)
    app.setWindowIcon(theme_mod.make_app_icon())
    app.setStyle("Fusion")

    if not _acquire_single_instance(app):
        QMessageBox.information(
            None, "FreeNote", "FreeNote 已在运行，请使用已打开的窗口（或从托盘图标打开）。"
        )
        return 1

    # 标准对话框按钮（是/否、确定/取消）跟随界面语言，避免中英混排
    translator = QTranslator(app)
    translations_path = QLibraryInfo.path(QLibraryInfo.LibraryPath.TranslationsPath)
    if translator.load("qtbase_zh_CN", translations_path):
        app.installTranslator(translator)
        app._translator = translator  # noqa: SLF001 - 保持引用，防止被 GC

    settings = Settings()
    store = NoteStore(config.db_path())
    session = UnlockSession()
    engine = PeerSyncEngine(store, settings)

    app.setStyleSheet(theme_mod.build_qss(theme_mod.effective_theme(settings.theme)))

    # 启用应用锁时先解锁（与 Android 的锁屏一致）
    if settings.pin_hash:
        pin = PinDialog.request(None, settings, "FreeNote", "应用已启用锁屏 PIN，请输入 PIN 解锁")
        if pin is None:
            engine.stop()
            store.close()
            return 0
        session.set_pin(pin)

    if not engine.start():
        # 端口被占用等异常：仍可离线使用，设置里改端口后可重启服务
        print("FreeNote: P2P 服务未能启动，应用将以离线模式运行", file=sys.stderr)

    window = MainWindow(store, settings, session, engine)
    window.show()
    try:
        return app.exec()
    finally:
        engine.stop()
        store.close()


if __name__ == "__main__":
    raise SystemExit(main())
