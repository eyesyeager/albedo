import hashlib
import hmac
import secrets
import string
from datetime import datetime, timedelta, timezone

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from cryptography.fernet import Fernet, InvalidToken, MultiFernet

from app.core.config import get_settings

_hasher = PasswordHasher(time_cost=2, memory_cost=64 * 1024, parallelism=2, hash_len=32, salt_len=16)
_ALPHABET = string.ascii_lowercase + string.digits


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def iso_after(**kwargs: int | float) -> str:
    return (datetime.now(timezone.utc) + timedelta(**kwargs)).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def hash_secret(value: str) -> str:
    return _hasher.hash(value)


def verify_secret(hashed: str, candidate: str) -> bool:
    try:
        _hasher.verify(hashed, candidate)
        return True
    except (VerifyMismatchError, ValueError, TypeError):
        return False


def constant_time_equal(left: str, right: str) -> bool:
    return hmac.compare_digest(hashlib.sha256(left.encode()).digest(), hashlib.sha256(right.encode()).digest())


def suffix_of(value: str) -> str:
    return value[-4:] if len(value) >= 4 else value


def new_id(prefix: str, length: int = 6) -> str:
    return prefix + "".join(secrets.choice(_ALPHABET) for _ in range(length))


def new_secret(prefix: str, length: int = 32) -> str:
    return prefix + secrets.token_urlsafe(length)


def new_session_id() -> str:
    return secrets.token_urlsafe(32)


def _fernet() -> MultiFernet:
    keys = get_settings().fernet_keys
    if not keys:
        raise RuntimeError("SECRET_KEY is not configured")
    return MultiFernet([Fernet(key) for key in keys])


def encrypt_secret(value: str) -> str:
    return _fernet().encrypt(value.encode()).decode()


def decrypt_secret(value: str) -> str | None:
    try:
        return _fernet().decrypt(value.encode()).decode()
    except (InvalidToken, ValueError):
        return None


def rotate_secret(value: str) -> str | None:
    """把用旧密钥加密的密文重加密为当前密钥；无法解密时返回 None。"""
    try:
        return _fernet().rotate(value.encode()).decode()
    except (InvalidToken, ValueError):
        return None
