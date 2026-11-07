"""系统配置读写服务。

所有业务可调参数都存放在 `system_configs` 表，运行期从数据库读取并同步到进程内配置，
源码里的 `default_system_config()` 只用于首次初始化，不再是运行时的取值来源。
"""

from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import apply_runtime_config
from app.core.security import now_iso
from app.db import models as m
from app.db.system_config_defaults import default_system_config

_LIMITS: dict[str, Any] = {}


def limit(key: str, default: Any = None) -> Any:
    """供同步代码读取数据库下发的业务限额（由 get_configs 刷新）。"""
    return _LIMITS.get(key, default)


def _refresh(config: dict[str, Any]) -> None:
    _LIMITS.clear()
    _LIMITS.update(config.get("limits", {}))
    apply_runtime_config(config.get("runtime", {}))


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
    """读取并立即把 runtime 分组与业务限额同步到进程内配置。"""
    config = await all_configs(session)
    _refresh(config)
    return config


async def update_configs(session: AsyncSession, patch: dict[str, Any]) -> dict[str, Any]:
    from app.core.errors import ApiException

    defaults = default_system_config()
    unknown = set(patch) - set(defaults)
    if unknown:
        raise ApiException("validation_error", f"未知的系统配置项：{', '.join(sorted(unknown))}", 400)
    for key, value in patch.items():
        if not isinstance(value, type(defaults[key])):
            raise ApiException("validation_error", f"系统配置项 {key} 的类型不正确", 400)
        row = await session.get(m.SystemConfig, key)
        if row is None:
            row = m.SystemConfig(key=key, description=f"系统配置：{key}")
            session.add(row)
        row.value = value
        row.updated_at = now_iso()
    await session.flush()
    return await get_configs(session)
