"""Application paths and shared constants."""

from __future__ import annotations

import os
import sys
from pathlib import Path

TRASH_RETENTION_MS = 30 * 24 * 60 * 60 * 1000  # 30 天，与 Android 版一致
UNLOCK_SESSION_MS = 5 * 60 * 1000  # 解锁会话有效期 5 分钟
AUTO_SAVE_DELAY_MS = 1200

# ---------------------------------------------------------------------- P2P
# 组密钥与发现 tag 绑定「同步账户」（服务器账号或离线账户）：
# 同一局域网内不同账户即使口令相同也互相不可见、无法解密；
# 请求/响应正文使用组密钥 AES-256-GCM 加密（HMAC 签名覆盖密文）。
PROTOCOL_VERSION = 6
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
