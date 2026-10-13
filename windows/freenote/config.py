"""Application paths and shared constants."""

from __future__ import annotations

import ipaddress
import os
import sys
from pathlib import Path
from urllib.parse import urlparse

TRASH_RETENTION_MS = 30 * 24 * 60 * 60 * 1000  # 30 天，与 Android 版一致
UNLOCK_SESSION_MS = 5 * 60 * 1000  # 解锁会话有效期 5 分钟
AUTO_SAVE_DELAY_MS = 1200

# 同步口令最小长度：口令可被局域网被动监听者通过发现 tag 离线穷举（文档已说明），
# 8 位起步 + 建议用「生成随机口令」控制爆破成本。
MIN_PASSPHRASE_LENGTH = 8

# ---------------------------------------------------------------------- P2P
# 组密钥与发现 tag 绑定「同步账户」（服务器账号或离线账户）：
# 同一局域网内不同账户即使口令相同也互相不可见、无法解密；
# 请求/响应正文使用组密钥 AES-256-GCM 加密（HMAC 签名覆盖密文）。
# 协议 v7：锁定便签密文升级为 v2（随机盐 + PBKDF2 600000 次），v6 及以前客户端
# 无法解密；P2P 由协议版本门控，混合版本会收到明确的「请升级」提示。
PROTOCOL_VERSION = 7
DEFAULT_SYNC_PORT = 8765  # 每台设备内置的同步 HTTP 服务端口
DISCOVERY_PORT = 8766  # 局域网设备发现（UDP 组播）端口
MULTICAST_GROUP = "239.255.42.42"
DISCOVERY_INTERVAL_SEC = 8.0
SYNC_COOLDOWN_SEC = 4.0  # 同一设备两次自动同步之间的最小间隔
MANIFEST_SESSION_TTL_SEC = 120.0
AUTH_CLOCK_SKEW_MS = 5 * 60 * 1000  # 请求时间戳允许的偏差
# OWASP 建议：PBKDF2-HMAC-SHA256 至少 600000 次迭代。
# 发现广播的 tag 可被被动监听者当作口令离线穷举的校验值，提高迭代成本抬升爆破代价。
GROUP_KEY_ITERATIONS = 600_000

# ------------------------------------------------------------------ 附件（协议 v6）
# 附件按内容寻址：文件名 = sha256(字节) 前 16 位十六进制 + 小写扩展名。
# 可单文件 8MB；单次同步消息最多携带 8 张、合计 8MB 原始字节（超出下一轮继续）。
MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024
MAX_ATTACHMENTS_PER_MESSAGE = 8
MAX_ATTACHMENT_BYTES_PER_MESSAGE = 8 * 1024 * 1024
ATTACHMENT_INVENTORY_LIMIT = 5000  # 单个清单里最多列出的附件名数量


def data_dir() -> Path:
    """%APPDATA%/FreeNote on Windows, ~/.freenote elsewhere (dev/tests)."""
    override = os.environ.get("FREENOTE_DATA_DIR")
    if override:
        base = Path(override)
    elif sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", str(Path.home()))) / "FreeNote"
    else:
        base = Path.home() / ".freenote"
    base.mkdir(parents=True, exist_ok=True)
    return base


def db_path() -> Path:
    return data_dir() / "notes.db"


def attachments_dir() -> Path:
    path = data_dir() / "attachments"
    path.mkdir(parents=True, exist_ok=True)
    return path


def settings_path() -> Path:
    return data_dir() / "settings.json"


def host_of(text: str) -> str:
    """从「主机[:端口][/路径]」或完整 URL 中提取主机名（小写，去 IPv6 方括号）。"""
    value = (text or "").strip()
    if not value:
        return ""
    if "://" in value:
        try:
            return (urlparse(value).hostname or "").lower()
        except ValueError:
            return ""
    authority = value.split("/", 1)[0].split("@")[-1]
    if authority.startswith("["):  # [::1]:8000
        end = authority.find("]")
        return authority[1:end].lower() if end > 0 else ""
    if authority.count(":") == 1:  # host:port
        authority = authority.rsplit(":", 1)[0]
    return authority.lower()


def is_local_host(host: str) -> bool:
    """本机/内网/链路本地主机名返回 True（与 Android ServerUrl 的判定一致）。"""
    name = (host or "").strip().lower().strip("[]")
    if not name:
        return False
    if name in ("localhost", "127.0.0.1", "::1") or name.endswith(".local"):
        return True
    try:
        ip = ipaddress.ip_address(name)
    except ValueError:
        return False
    return ip.is_loopback or ip.is_private or ip.is_link_local


def is_plaintext_public_url(url: str) -> bool:
    """明文 HTTP 且目标不是本机/内网地址时返回 True（用于提示/阻断明文传输）。"""
    value = (url or "").strip()
    if not value:
        return False
    parsed = urlparse(value if "://" in value else f"//{value}")
    if (parsed.scheme or "").lower() != "http":
        return False
    host = (parsed.hostname or "").lower()
    if not host:
        return False
    return not is_local_host(host)
