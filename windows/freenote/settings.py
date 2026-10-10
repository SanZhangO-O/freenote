"""应用设置（JSON 文件，位于 %APPDATA%/FreeNote/settings.json）。

P2P 相关设置：
- device_id / device_name：本机身份（首次运行自动生成）；
- p2p_enabled：P2P 总开关，默认关闭；开启且配置口令后才会监听端口/参与发现；
- group_passphrase：同步口令，只有口令相同的设备才会互相发现并接受同步；
- p2p_account：离线账户名；未配置服务器账号时用它区分同步组（登录服务器后自动
  改用「服务器地址 + 用户名」作为同步账户）；推荐用「生成 UUID」填入随机唯一
  账户，复制到其他设备即可同组，无需注册服务器账号；
- sync_port：内置同步服务端口（0 = 自动分配）；
- peers：已知设备列表（自动发现 + 手动添加），用于显示与持久化。

服务器同步设置（与 Android 版共用 FreeNote 服务端）：
- server_url / server_username / server_token：服务端地址与登录令牌；
- server_auto_sync：服务器自动同步开关（间隔与 P2P 共用 sync_interval_min）。

凭据静态保护：同步口令与服务器令牌不落盘明文，Windows 上用 DPAPI
（按当前 Windows 用户加密，复制到其他机器/用户下无法解密，读取时按空处理）。

网络默认关闭：P2P 总开关/发现/自动同步/服务器自动同步默认均为关，且未开启
P2P 总开关或未配置同步口令前不监听任何端口、不发送组播——所有联网能力都
需要用户显式开启。
"""

from __future__ import annotations

import base64
import ctypes
import json
import os
import socket
import sys
import tempfile
import uuid
from pathlib import Path

from . import config

THEME_SYSTEM = "system"
THEME_LIGHT = "light"
THEME_DARK = "dark"

_PROTECTED_KEYS = ("group_passphrase", "server_token", "webdav_password")
_PROTECTED_PREFIX = "dpapi:"


class _DataBlob(ctypes.Structure):
    # DWORD cbData + 指针，避免依赖 ctypes.wintypes（在其他平台导入可能失败）
    _fields_ = [("cbData", ctypes.c_uint32), ("pbData", ctypes.c_void_p)]


def _dpapi(data: bytes, protect: bool) -> bytes | None:
    """调用 Windows DPAPI；失败或非 Windows 平台返回 None。"""
    if sys.platform != "win32":
        return None
    try:
        crypt32 = ctypes.windll.crypt32
        kernel32 = ctypes.windll.kernel32
        # 显式声明 argtypes/restype：64 位下避免指针被按 32 位截断
        blob_ptr = ctypes.POINTER(_DataBlob)
        for name in ("CryptProtectData", "CryptUnprotectData"):
            function = getattr(crypt32, name)
            function.argtypes = [
                blob_ptr,
                ctypes.c_wchar_p,
                blob_ptr,
                ctypes.c_void_p,
                ctypes.c_void_p,
                ctypes.c_uint32,
                blob_ptr,
            ]
            function.restype = ctypes.c_int
        kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        kernel32.LocalFree.restype = ctypes.c_void_p

        buffer = ctypes.create_string_buffer(data, len(data))
        blob_in = _DataBlob(len(data), ctypes.cast(buffer, ctypes.c_void_p))
        blob_out = _DataBlob()
        function = crypt32.CryptProtectData if protect else crypt32.CryptUnprotectData
        ok = function(
            ctypes.byref(blob_in),
            None,
            None,
            None,
            None,
            0x1,  # CRYPTPROTECT_UI_FORBIDDEN：不弹 UI，失败直接返回
            ctypes.byref(blob_out),
        )
        if not ok:
            return None
        try:
            return ctypes.string_at(blob_out.pbData, blob_out.cbData)
        finally:
            kernel32.LocalFree(blob_out.pbData)
    except Exception:  # noqa: BLE001 - 加密不可用时降级为原样存储，不阻塞应用
        return None


def protect_secret(value: str) -> str:
    if not value or value.startswith(_PROTECTED_PREFIX):
        return value
    encrypted = _dpapi(value.encode("utf-8"), protect=True)
    if encrypted is None:
        return value
    return _PROTECTED_PREFIX + base64.b64encode(encrypted).decode("ascii")


def try_unprotect_secret(value: str) -> tuple[bool, str]:
    """返回 (是否可还原, 还原值)；未受保护的值原样返回 (True, value)。"""
    if not isinstance(value, str) or not value.startswith(_PROTECTED_PREFIX):
        return True, (value or "")
    try:
        encrypted = base64.b64decode(value[len(_PROTECTED_PREFIX):])
    except Exception:  # noqa: BLE001
        return False, ""
    decrypted = _dpapi(encrypted, protect=False)
    if decrypted is None:
        return False, ""
    try:
        return True, decrypted.decode("utf-8")
    except UnicodeDecodeError:
        return False, ""

_DEFAULTS = {
    "theme": THEME_SYSTEM,
    "pin_hash": None,
    "device_id": "",
    "device_name": "",
    "group_passphrase": "",
    "p2p_account": "",
    "p2p_enabled": False,
    "sync_port": config.DEFAULT_SYNC_PORT,
    "discovery_enabled": False,
    "auto_sync": False,
    "sync_interval_min": 5,
    "peers": [],
    "server_url": "",
    "server_username": "",
    "server_token": "",
    "server_auto_sync": False,
    "server_tls_no_verify": False,
    "auto_backup_enabled": False,
    "auto_backup_dir": "",
    "auto_backup_interval_hours": 24,
    "webdav_url": "",
    "webdav_user": "",
    "webdav_password": "",
    "window_width": 1100,
    "window_height": 760,
}


def _value_type_ok(value, default) -> bool:
    """load 时按默认值的类型校验，不符则回退默认值，避免脏数据导致启动崩溃。"""
    if isinstance(default, bool):
        return isinstance(value, bool)
    if isinstance(default, int):
        return isinstance(value, int) and not isinstance(value, bool)
    if isinstance(default, str):
        return isinstance(value, str)
    if isinstance(default, list):
        return isinstance(value, list)
    return value is None or isinstance(value, str)


def default_device_name() -> str:
    try:
        name = socket.gethostname().strip()
    except Exception:  # noqa: BLE001
        name = ""
    return name or "未命名设备"


class Settings:
    def __init__(self, path: Path | None = None):
        self.path = Path(path) if path else config.settings_path()
        self._values = dict(_DEFAULTS)
        # 无法解密（换机器/换用户）但需要原样保留的凭据密文
        self._sealed_secrets: dict[str, str] = {}
        self.load()
        if not self.get("device_id"):
            self._values["device_id"] = str(uuid.uuid4())
        if not self.get("device_name"):
            self._values["device_name"] = default_device_name()
        self.save()

    def load(self) -> None:
        if not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except Exception:
            return
        if isinstance(data, dict):
            for key, default in _DEFAULTS.items():
                if key in data and _value_type_ok(data[key], default):
                    self._values[key] = data[key]
            for key in _PROTECTED_KEYS:
                raw = self._values.get(key)
                if not isinstance(raw, str) or not raw:
                    continue
                ok, plain = try_unprotect_secret(raw)
                if ok:
                    self._values[key] = plain
                else:
                    # 换机器/换用户导致解不开：内存里按空处理，
                    # 但保存时必须保留原密文，不能在用户重设前把它抹掉
                    self._values[key] = ""
                    self._sealed_secrets[key] = raw

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        # 凭据（同步口令 / 服务器令牌）落盘前用 DPAPI 保护
        data = dict(self._values)
        for key in _PROTECTED_KEYS:
            value = data.get(key)
            if isinstance(value, str) and value:
                data[key] = protect_secret(value)
            elif key in self._sealed_secrets:
                # 解不开的旧密文按原样写回（用户重设后会清除该备份）
                data[key] = self._sealed_secrets[key]
        text = json.dumps(data, ensure_ascii=False, indent=2)
        fd, tmp = tempfile.mkstemp(dir=str(self.path.parent), suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(text)
            os.replace(tmp, self.path)
        except Exception:
            if os.path.exists(tmp):
                os.remove(tmp)
            raise

    def get(self, key: str, default=None):
        return self._values.get(key, default)

    def set(self, key: str, value, save: bool = True) -> None:
        self._values[key] = value
        # 显式赋值视为用户意图：丢弃无法解密、仅作保留的旧密文
        self._sealed_secrets.pop(key, None)
        if save:
            self.save()

    # ------------------------------------------------------------------ 便捷访问

    @property
    def theme(self) -> str:
        value = self.get("theme", THEME_SYSTEM)
        return value if value in (THEME_SYSTEM, THEME_LIGHT, THEME_DARK) else THEME_SYSTEM

    @theme.setter
    def theme(self, value: str) -> None:
        self.set("theme", value)

    @property
    def pin_hash(self) -> str | None:
        value = self.get("pin_hash")
        return value or None

    @pin_hash.setter
    def pin_hash(self, value: str | None) -> None:
        self.set("pin_hash", value)

    # ------------------------------------------------------------------ P2P

    @property
    def device_id(self) -> str:
        return self.get("device_id") or ""

    @property
    def device_name(self) -> str:
        return (self.get("device_name") or "").strip() or default_device_name()

    @device_name.setter
    def device_name(self, value: str) -> None:
        # 设备名会放进 UDP 公告：限长避免超过单个数据报
        self.set("device_name", (value.strip() or default_device_name())[:64])

    @property
    def group_passphrase(self) -> str:
        return self.get("group_passphrase") or ""

    @group_passphrase.setter
    def group_passphrase(self, value: str) -> None:
        self.set("group_passphrase", value.strip()[:256])

    @property
    def p2p_account(self) -> str:
        """离线账户名：仅在未配置服务器账号时作为同步账户。"""
        return (self.get("p2p_account") or "").strip()

    @p2p_account.setter
    def p2p_account(self, value: str) -> None:
        self.set("p2p_account", (value or "").strip()[:64])

    @property
    def sync_port(self) -> int:
        try:
            return max(0, min(65535, int(self.get("sync_port", config.DEFAULT_SYNC_PORT))))
        except (TypeError, ValueError):
            return config.DEFAULT_SYNC_PORT

    @sync_port.setter
    def sync_port(self, value: int) -> None:
        self.set("sync_port", max(0, min(65535, int(value))))

    @property
    def p2p_enabled(self) -> bool:
        """P2P 总开关：默认关闭，开启且配置口令后才监听端口、参与发现。"""
        return bool(self.get("p2p_enabled", False))

    @p2p_enabled.setter
    def p2p_enabled(self, value: bool) -> None:
        self.set("p2p_enabled", bool(value))

    @property
    def discovery_enabled(self) -> bool:
        return bool(self.get("discovery_enabled", False))

    @discovery_enabled.setter
    def discovery_enabled(self, value: bool) -> None:
        self.set("discovery_enabled", bool(value))

    @property
    def auto_sync(self) -> bool:
        return bool(self.get("auto_sync", False))

    @auto_sync.setter
    def auto_sync(self, value: bool) -> None:
        self.set("auto_sync", bool(value))

    @property
    def sync_interval_min(self) -> int:
        try:
            return max(1, int(self.get("sync_interval_min", 5)))
        except (TypeError, ValueError):
            return 5

    @sync_interval_min.setter
    def sync_interval_min(self, value: int) -> None:
        self.set("sync_interval_min", int(value))

    def peers(self) -> list[dict]:
        value = self.get("peers", [])
        if not isinstance(value, list):
            return []
        peers: list[dict] = []
        for item in value:
            if isinstance(item, dict) and item.get("id"):
                peers.append(dict(item))
        return peers

    def set_peers(self, peers: list[dict]) -> None:
        self.set("peers", [dict(p) for p in peers])

    def p2p_ready(self) -> bool:
        return bool(self.group_passphrase)

    # ------------------------------------------------------------------ 服务器同步

    @property
    def server_url(self) -> str:
        return (self.get("server_url") or "").rstrip("/")

    @server_url.setter
    def server_url(self, value: str) -> None:
        self.set("server_url", (value or "").strip().rstrip("/"))

    @property
    def server_username(self) -> str:
        return self.get("server_username") or ""

    @server_username.setter
    def server_username(self, value: str) -> None:
        self.set("server_username", (value or "").strip())

    @property
    def server_token(self) -> str:
        return self.get("server_token") or ""

    @server_token.setter
    def server_token(self, value: str) -> None:
        self.set("server_token", value or "")

    @property
    def server_auto_sync(self) -> bool:
        return bool(self.get("server_auto_sync", False))

    @server_auto_sync.setter
    def server_auto_sync(self, value: bool) -> None:
        self.set("server_auto_sync", bool(value))

    @property
    def server_tls_no_verify(self) -> bool:
        """HTTPS 证书校验开关：仅自签名证书的服务器才需要关闭校验。"""
        return bool(self.get("server_tls_no_verify", False))

    @server_tls_no_verify.setter
    def server_tls_no_verify(self, value: bool) -> None:
        self.set("server_tls_no_verify", bool(value))

    def server_ready(self) -> bool:
        return bool(self.server_url and self.server_token)

    # ------------------------------------------------------------------ 自动备份 / WebDAV

    @property
    def auto_backup_enabled(self) -> bool:
        return bool(self.get("auto_backup_enabled", False))

    @auto_backup_enabled.setter
    def auto_backup_enabled(self, value: bool) -> None:
        self.set("auto_backup_enabled", bool(value))

    @property
    def auto_backup_dir(self) -> str:
        return (self.get("auto_backup_dir") or "").strip()

    @auto_backup_dir.setter
    def auto_backup_dir(self, value: str) -> None:
        self.set("auto_backup_dir", (value or "").strip())

    @property
    def auto_backup_interval_hours(self) -> int:
        try:
            return max(1, min(720, int(self.get("auto_backup_interval_hours", 24))))
        except (TypeError, ValueError):
            return 24

    @auto_backup_interval_hours.setter
    def auto_backup_interval_hours(self, value: int) -> None:
        self.set("auto_backup_interval_hours", max(1, min(720, int(value))))

    @property
    def webdav_url(self) -> str:
        return (self.get("webdav_url") or "").strip().rstrip("/")

    @webdav_url.setter
    def webdav_url(self, value: str) -> None:
        self.set("webdav_url", (value or "").strip().rstrip("/"))

    @property
    def webdav_user(self) -> str:
        return (self.get("webdav_user") or "").strip()

    @webdav_user.setter
    def webdav_user(self, value: str) -> None:
        self.set("webdav_user", (value or "").strip())

    @property
    def webdav_password(self) -> str:
        return self.get("webdav_password") or ""

    @webdav_password.setter
    def webdav_password(self, value: str) -> None:
        self.set("webdav_password", value or "")

    def auto_backup_ready(self) -> bool:
        return self.auto_backup_enabled and bool(self.auto_backup_dir)
