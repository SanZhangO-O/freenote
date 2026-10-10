"""全局热键（Windows）：Ctrl+Alt+N 唤起主窗口并新建便签。

使用 user32.RegisterHotKey + 专用线程 GetMessage 消息循环实现（不引入额外依赖）。
回调在热键线程触发，调用方需自行切回 UI 线程（Qt 跨线程信号/队列连接）。
注册失败（热键被其他程序占用等）时静默退出，不影响应用其他功能。
"""

from __future__ import annotations

import ctypes
import threading
from ctypes import wintypes

_MOD_ALT = 0x0001
_MOD_CONTROL = 0x0002
_VK_N = 0x4E
_WM_HOTKEY = 0x0312
_WM_QUIT = 0x0012
_HOTKEY_ID = 0xF00D


class GlobalHotkey(threading.Thread):
    """监听 Ctrl+Alt+N；stop() 后退出监听线程。"""

    def __init__(self, callback):
        super().__init__(daemon=True, name="freenote-hotkey")
        self._callback = callback
        self._stop_event = threading.Event()
        self._thread_id: int = 0
        self.registered = False

    def run(self) -> None:  # noqa: D102
        if not hasattr(ctypes, "windll"):  # 非 Windows（开发/测试环境）：静默不启用
            return
        user32 = ctypes.windll.user32
        kernel32 = ctypes.windll.kernel32
        self._thread_id = int(kernel32.GetCurrentThreadId())
        if not user32.RegisterHotKey(None, _HOTKEY_ID, _MOD_CONTROL | _MOD_ALT, _VK_N):
            return
        self.registered = True
        msg = wintypes.MSG()
        try:
            while not self._stop_event.is_set():
                result = user32.GetMessageW(ctypes.byref(msg), None, 0, 0)
                if result in (0, -1):
                    break
                if msg.message == _WM_HOTKEY and not self._stop_event.is_set():
                    try:
                        self._callback()
                    except Exception:  # noqa: BLE001 - 热键回调异常不应终止监听
                        pass
        finally:
            user32.UnregisterHotKey(None, _HOTKEY_ID)

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread_id:
            try:
                ctypes.windll.user32.PostThreadMessageW(self._thread_id, _WM_QUIT, 0, 0)
            except Exception:  # noqa: BLE001 - 退出流程中尽力而为
                pass
