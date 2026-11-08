"""幂等的系统基础数据初始化。

只创建：工作区设置行、系统配置项、管理面访问 Key、内置工具目录。
不写入任何演示数据，也不提供「恢复演示数据」能力。
"""

import re
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import encrypt_secret, hash_secret, now_iso
from app.db import models as m
from app.db.seed_data import make_seed
from app.services.system_config import ensure_system_configs

CAMEL_TO_SNAKE = re.compile(r"(?<!^)(?=[A-Z])")

BUSINESS_TABLES: tuple[type, ...] = (
    m.WorkspaceSetting,
    m.Agent,
    m.AgentMount,
    m.ModelResource,
    m.ModelPublication,
    m.McpServer,
    m.McpTool,
    m.McpCallRecord,
    m.Skill,
    m.SkillVersion,
    m.SkillLoadRecord,
    m.BuiltinTool,
    m.Run,
    m.ChatMessage,
    m.LogEntry,
    m.ExportTask,
    m.Notification,
    m.TrendDaily,
)


def to_row(data: dict[str, Any], model: type, *, drop: set[str] | None = None, aliases: dict[str, str] | None = None) -> dict[str, Any]:
    columns = {column.name for column in model.__table__.columns}
    skipped = drop or set()
    mapping = aliases or {}
    row: dict[str, Any] = {}
    for key, value in data.items():
        if key in skipped:
            continue
        column_name = mapping.get(key, CAMEL_TO_SNAKE.sub("_", key).lower())
        if column_name in columns:
            row[column_name] = value
    return row


async def table_counts(session: AsyncSession) -> dict[str, int]:
    result: dict[str, int] = {}
    for model in (*BUSINESS_TABLES, m.SystemConfig, m.Credential, m.Session):
        count = await session.scalar(select(func.count()).select_from(model))
        result[model.__tablename__] = int(count or 0)
    return result


async def seed_workspace(session: AsyncSession) -> dict[str, int]:
    exists = await session.get(m.WorkspaceSetting, 1)
    data = make_seed()

    if exists is None:
        settings_row = to_row(data["settings"], m.WorkspaceSetting, drop={"accessKey"})
        settings_row["id"] = 1
        settings_row["updated_at"] = now_iso()
        session.add(m.WorkspaceSetting(**settings_row))

    key_exists = await session.scalar(select(m.Credential.id).where(m.Credential.type == "management_access_key"))
    if key_exists is None:
        stamp = now_iso()
        plain = data["settings"]["accessKey"]
        session.add(m.Credential(
            id="cred-management_access_key-workspace",
            type="management_access_key",
            hash=hash_secret(plain),
            cipher=encrypt_secret(plain),
            suffix=data["settings"]["accessKeySuffix"],
            resource_id="workspace",
            rotated_at=stamp,
            created_at=stamp,
        ))

    for tool in data["tools"]:
        found = await session.scalar(select(m.BuiltinTool.id).where(m.BuiltinTool.key == tool["key"]))
        if found is None:
            session.add(m.BuiltinTool(**to_row(tool, m.BuiltinTool)))

    await ensure_system_configs(session)
    await session.commit()
    return await table_counts(session)
