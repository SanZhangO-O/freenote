"""字段级加密，与 Android 版 CryptoUtils 完全兼容。

- 锁定便签：PBKDF2-HMAC-SHA256(PIN, 固定盐, 12000 次) -> 32 字节密钥，
  AES-256-GCM，密文 = Base64(IV(12) + CipherText + Tag(16))。
- PIN 校验哈希：PBKDF2-HMAC-SHA256(PIN, 随机盐, 60000 次)，
  存储格式 "迭代次数:盐(Base64):派生密钥(Base64)"。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import os
import secrets

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

_FIXED_SALT = b"freenote-fixed-salt-v1"
_NOTE_ITERATIONS = 12000
_KEY_LENGTH = 32
_IV_LENGTH = 12

PIN_ITERATIONS = 60000
PIN_SALT_BYTES = 16

PIN_MIN_LENGTH = 4
PIN_MAX_LENGTH = 6


def is_complete_pin(pin: str) -> bool:
    """可提交的 PIN：4~6 位纯数字。"""
    return PIN_MIN_LENGTH <= len(pin) <= PIN_MAX_LENGTH and pin.isdigit()


def _derive_note_key(pin: str) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", pin.encode("utf-8"), _FIXED_SALT, _NOTE_ITERATIONS, _KEY_LENGTH)


def encrypt(plain: str, pin: str) -> str:
    """加密锁定便签字段；空字符串原样返回（与 Android 版一致）。"""
    if plain == "":
        return ""
    iv = os.urandom(_IV_LENGTH)
    cipher_text = AESGCM(_derive_note_key(pin)).encrypt(iv, plain.encode("utf-8"), None)
    return base64.b64encode(iv + cipher_text).decode("ascii")


def decrypt(cipher_b64: str, pin: str) -> str | None:
    """解密失败（PIN 错误或数据损坏）返回 None，与 Android 版行为一致。"""
    if cipher_b64 == "":
        return ""
    try:
        raw = base64.b64decode(cipher_b64)
        iv, cipher_text = raw[:_IV_LENGTH], raw[_IV_LENGTH:]
        return AESGCM(_derive_note_key(pin)).decrypt(iv, cipher_text, None).decode("utf-8")
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
