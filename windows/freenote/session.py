"""解锁会话：内存中保存 PIN，5 分钟后过期（与 Android 版一致）。"""

from __future__ import annotations

from . import config


class UnlockSession:
    def __init__(self, timeout_ms: int = config.UNLOCK_SESSION_MS):
        self._timeout_ms = timeout_ms
        self._pin: str | None = None
        self._expires_at = 0

    def set_pin(self, pin: str) -> None:
        self._pin = pin
        import time

        self._expires_at = int(time.time() * 1000) + self._timeout_ms

    def get_pin(self) -> str | None:
        if self._pin is None:
            return None
        import time

        if int(time.time() * 1000) > self._expires_at:
            self.clear()
            return None
        return self._pin

    def clear(self) -> None:
        self._pin = None
        self._expires_at = 0
