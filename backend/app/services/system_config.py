"""系统配置读写服务。

运行期以 `system_configs` 表为唯一真相；进程内缓存由 Redis 配置版本号驱动失效，
避免多 uvicorn / arq worker 之间长期持有不同的限额与运行时参数。
"""

import time
import uuid
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import apply_runtime_config
from app.core.errors import ApiException
from app.core.redis_client import try_get, try_set
from app.core.security import now_iso
from app.db import models as m
from app.db.system_config_defaults import default_system_config

_LIMITS: dict[str, Any] = {}
_CONFIG: dict[str, Any] | None = None
_CONFIG_VERSION = ""
_CONFIG_CACHED_AT = 0.0

_CONFIG_VERSION_KEY = "albedo:system-config:version"
_CONFIG_VERSION_TTL = 24 * 3600
_LOCAL_CACHE_TTL = 5.0

# 安全开关只能由部署期环境变量控制，禁止通过管理面 HTTP 接口关闭 SSRF 防护。
PROTECTED_LIMIT_KEYS = frozenset({"mcpAllowPrivateUrls"})


def limit(key: str, default: Any = None) -> Any:
    """供同步代码读取数据库下发的业务限额（由 get_configs 刷新）。"""
    return _LIMITS.get(key, default)


def _refresh(config: dict[str, Any]) -> None:
    _LIMITS.clear()
    _LIMITS.update(config.get("limits", {}))
    apply_runtime_config(config.get("runtime", {}))


def _set_local(config: dict[str, Any], version: str) -> None:
    global _CONFIG, _CONFIG_VERSION, _CONFIG_CACHED_AT
    _CONFIG = config
    _CONFIG_VERSION = version
    _CONFIG_CACHED_AT = time.monotonic()
    _refresh(config)


def _validate_group(key: str, value: dict[str, Any], defaults: dict[str, Any]) -> None:
    """逐字段校验分组内部值，拒绝未知键、类型错误与受保护的安全开关。"""
    unknown = set(value) - set(defaults)
    if unknown:
        raise ApiException("validation_error", f"系统配置 {key} 含未知项：{', '.join(sorted(unknown))}", 400)
    for sub_key, sub_value in value.items():
        if sub_key in PROTECTED_LIMIT_KEYS:
            raise ApiException(
                "validation_error",
                f"配置项 {sub_key} 属于部署期安全开关，不能通过接口修改",
                400,
            )
        expected = defaults[sub_key]
        if isinstance(expected, bool):
            valid = isinstance(sub_value, bool)
        elif isinstance(expected, (int, float)):
            valid = not isinstance(sub_value, bool) and isinstance(sub_value, (int, float))
        elif isinstance(expected, str):
            valid = isinstance(sub_value, str)
        else:
            valid = isinstance(sub_value, type(expected))
        if not valid:
            raise ApiException("validation_error", f"系统配置项 {key}.{sub_key} 的值类型不合法", 400)


async def ensure_system_configs(session: AsyncSession) -> None:
    defaults = default_system_config()
    existing = {row.key for row in (await session.scalars(select(m.SystemConfig.key))).all()}
    for key, value in defaults.items():
        if key not in existing:
            session.add(m.SystemConfig(key=key, value=value, description=f"系统配置：{key}", updated_at=now_iso()))


async def all_configs(session: AsyncSession) -> dict[str, Any]:
    defaults = default_system_config()
    result = dict(defaults)
    for row in (await session.scalars(select(m.SystemConfig))).all():
        if row.key not in defaults:
            continue
        # 分组配置浅合并：新增的默认项无需数据迁移即可生效，且保留库中的覆盖值。
        if isinstance(defaults[row.key], dict) and isinstance(row.value, dict):
            merged = {**defaults[row.key], **row.value}
            if row.key == "defaults":
                # defaults 下还有 agent/model/mcp 等子分组，需要再合并一层。
                for sub_key, sub_default in defaults[row.key].items():
                    if isinstance(sub_default, dict) and isinstance(merged.get(sub_key), dict):
                        merged[sub_key] = {**sub_default, **merged[sub_key]}
            result[row.key] = merged
        else:
            result[row.key] = row.value
    return result


async def get_configs(session: AsyncSession) -> dict[str, Any]:
    """命中缓存则直接复用；版本号变化（或 Redis 不可用时的短 TTL 到期）才重新查库。"""
    remote_version = str(await try_get(_CONFIG_VERSION_KEY) or "")
    stale = time.monotonic() - _CONFIG_CACHED_AT > _LOCAL_CACHE_TTL
    if _CONFIG is not None and remote_version == _CONFIG_VERSION and not stale:
        _refresh(_CONFIG)
        return dict(_CONFIG)
    config = await all_configs(session)
    _set_local(config, remote_version)
    return config


async def update_configs(session: AsyncSession, patch: dict[str, Any]) -> dict[str, Any]:
    defaults = default_system_config()
    unknown = set(patch) - set(defaults)
    if unknown:
        raise ApiException("validation_error", f"未知的系统配置项：{', '.join(sorted(unknown))}", 400)
    for key, value in list(patch.items()):
        if not isinstance(value, type(defaults[key])):
            raise ApiException("validation_error", f"系统配置项 {key} 的类型不正确", 400)
        if isinstance(value, dict) and isinstance(defaults[key], dict) and key in ("limits", "runtime"):
            # 前端常把整个 limits 回传，受保护键必须原样剔除而不是拒绝整次保存。
            value = {k: v for k, v in value.items() if k not in PROTECTED_LIMIT_KEYS}
            patch[key] = value
            _validate_group(key, value, defaults[key])
        row = await session.get(m.SystemConfig, key)
        if row is None:
            row = m.SystemConfig(key=key, description=f"系统配置：{key}")
            session.add(row)
        row.value = value
        row.updated_at = now_iso()
    await session.flush()
    config = await all_configs(session)
    # 版本号广播让其它进程在下次读取时主动失效缓存。
    version = f"{now_iso()}-{uuid.uuid4().hex[:8]}"
    await try_set(_CONFIG_VERSION_KEY, version, _CONFIG_VERSION_TTL)
    _set_local(config, version)
    return config
