"""FreeNote for Windows — 桌面便签应用。

数据格式与 Android 版兼容；同步支持两种方式：
- 局域网 P2P 直连：正文 AES-256-GCM 加密 + HMAC-SHA256 签名，无需服务器；
- FreeNote 服务端中转：与 Android 版共用账号，可跨网络同步。
"""

__version__ = "1.0.0"
