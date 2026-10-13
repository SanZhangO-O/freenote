"""字段级加密，与 Android 版 CryptoUtils 完全兼容。

- 锁定便签（当前格式 v2）：每条密文自带随机盐，PBKDF2-HMAC-SHA256 600000 次
  → AES-256-GCM；密文 = "v2:" + 迭代数 + ":" + Base64(盐(16) + IV(12) + 密文 + Tag(16))。
  随机盐使预计算（彩虹表）失效；迭代数写进密文，便于后续单独提升强度。
- 旧格式（v1）：固定盐 "freenote-fixed-salt-v1" + 12000 次，密文 = Base64(IV + 密文 + Tag)；
  仅保留解密兼容（旧版本写入的锁定便签仍能解开），新加密一律使用 v2。
- PIN 校验哈希：PBKDF2-HMAC-SHA256(PIN, 随机盐, 600000 次)，
  存储格式 "迭代次数:盐(Base64):派生密钥(Base64)"（旧哈希按其中记录的迭代数校验）。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import os
import secrets

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# v1（旧）参数：仅用于解密历史密文，不再用于新加密
_LEGACY_FIXED_SALT = b"freenote-fixed-salt-v1"
_LEGACY_NOTE_ITERATIONS = 12000

# v2（当前）参数
_NOTE_FORMAT_PREFIX = "v2:"
NOTE_ITERATIONS = 600_000
_SALT_LENGTH = 16
_IV_LENGTH = 12
_TAG_LENGTH = 16
# 解密时允许的迭代数范围：防御畸形/恶意密文造成的 CPU 拒绝服务。
# 上限只留当前默认值的少量余量（未来升级同步改两端），避免同步进来的
# 恶意密文以迭代数放大 CPU 消耗。
_MIN_ITERATIONS = 1000
_MAX_ITERATIONS = 1_000_000

_KEY_LENGTH = 32

PIN_ITERATIONS = 600_000
PIN_SALT_BYTES = 16

PIN_MIN_LENGTH = 4
PIN_MAX_LENGTH = 12


def is_complete_pin(pin: str) -> bool:
    """可提交的 PIN：4~12 位纯数字。"""
    return PIN_MIN_LENGTH <= len(pin) <= PIN_MAX_LENGTH and pin.isdigit()


def _derive_note_key(pin: str, salt: bytes, iterations: int) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", pin.encode("utf-8"), salt, iterations, _KEY_LENGTH)


def encrypt(plain: str, pin: str) -> str:
    """加密锁定便签字段；空字符串原样返回（与 Android 版一致）。"""
    if plain == "":
        return ""
    salt = secrets.token_bytes(_SALT_LENGTH)
    iv = os.urandom(_IV_LENGTH)
    cipher_text = AESGCM(_derive_note_key(pin, salt, NOTE_ITERATIONS)).encrypt(
        iv, plain.encode("utf-8"), None
    )
    payload = base64.b64encode(salt + iv + cipher_text).decode("ascii")
    return f"{_NOTE_FORMAT_PREFIX}{NOTE_ITERATIONS}:{payload}"


def decrypt(cipher_b64: str, pin: str) -> str | None:
    """解密锁定便签字段：v2 与 v1 均支持；失败（PIN 错误或数据损坏）返回 None。"""
    if cipher_b64 == "":
        return ""
    try:
        if cipher_b64.startswith(_NOTE_FORMAT_PREFIX):
            _, iterations_text, payload = cipher_b64.split(":", 2)
            iterations = int(iterations_text)
            if not (_MIN_ITERATIONS <= iterations <= _MAX_ITERATIONS):
                return None
            raw = base64.b64decode(payload, validate=True)
            if len(raw) <= _SALT_LENGTH + _IV_LENGTH + _TAG_LENGTH:
                return None
            salt = raw[:_SALT_LENGTH]
            iv = raw[_SALT_LENGTH : _SALT_LENGTH + _IV_LENGTH]
            cipher_text = raw[_SALT_LENGTH + _IV_LENGTH :]
            key = _derive_note_key(pin, salt, iterations)
            return AESGCM(key).decrypt(iv, cipher_text, None).decode("utf-8")
        # v1 兼容：固定盐 + 12000 次，Base64(IV + 密文 + Tag)
        raw = base64.b64decode(cipher_b64)
        iv, cipher_text = raw[:_IV_LENGTH], raw[_IV_LENGTH:]
        key = _derive_note_key(pin, _LEGACY_FIXED_SALT, _LEGACY_NOTE_ITERATIONS)
        return AESGCM(key).decrypt(iv, cipher_text, None).decode("utf-8")
    except Exception:
        return None


def _derive_pin_key(pin: str, salt: bytes, iterations: int) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", pin.encode("utf-8"), salt, iterations, _KEY_LENGTH)


def hash_pin(pin: str) -> str:
    salt = secrets.token_bytes(PIN_SALT_BYTES)
    derived = _derive_pin_key(pin, salt, PIN_ITERATIONS)
    return "{}:{}:{}".format(
        PIN_ITERATIONS,
        base64.b64encode(salt).decode("ascii"),
        base64.b64encode(derived).decode("ascii"),
    )


def verify_pin(pin: str, stored_hash: str | None) -> bool:
    if not stored_hash:
        return False
    parts = stored_hash.split(":")
    if len(parts) != 3:
        return False
    try:
        iterations = int(parts[0])
        salt = base64.b64decode(parts[1])
        expected = base64.b64decode(parts[2])
    except Exception:
        return False
    derived = _derive_pin_key(pin, salt, iterations)
    return hmac.compare_digest(derived, expected)
