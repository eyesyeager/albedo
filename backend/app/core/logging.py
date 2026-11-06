import contextvars
import logging
import re
from typing import Any

import structlog

_request_id: contextvars.ContextVar[str] = contextvars.ContextVar("albedo_request_id", default="req_unknown")

SENSITIVE_PATTERN = re.compile(
    r"(api[_-]?key|access[_-]?key|authorization|bearer|password|token|secret)[\"'\s:=]+([^\s,}\"']{4,})",
    re.IGNORECASE,
)
BEARER_PATTERN = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._\-]{6,}")
SK_PATTERN = re.compile(r"\bsk-[A-Za-z0-9._\-]{6,}")


def set_request_id(request_id: str) -> contextvars.Token[str]:
    return _request_id.set(request_id)


def reset_request_id(token: contextvars.Token[str]) -> None:
    _request_id.reset(token)


def get_request_id() -> str:
    return _request_id.get()


def mask_text(value: str) -> str:
    masked = BEARER_PATTERN.sub("Bearer ••••••••", value)
    masked = SK_PATTERN.sub("sk-••••••••", masked)
    return SENSITIVE_PATTERN.sub(lambda m: f"{m.group(1)}=••••••••", masked)


SENSITIVE_KEYS = ("api_key", "apikey", "access_key", "accesskey", "full_key", "password", "secret", "authorization", "cipher", "bearer")


def sanitize(value: Any) -> Any:
    if isinstance(value, str):
        return mask_text(value)
    if isinstance(value, dict):
        return {k: ("••••••••" if any(s in k.lower() for s in SENSITIVE_KEYS) else sanitize(v)) for k, v in value.items()}
    if isinstance(value, list):
        return [sanitize(item) for item in value]
    return value


def configure_logging() -> None:
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str = "albedo") -> structlog.stdlib.BoundLogger:
    return structlog.get_logger(name)
