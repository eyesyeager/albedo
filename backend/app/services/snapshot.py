import json
from collections import defaultdict
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import models as m
from app.services.system_config import get_configs
from app.services.trends import build_trend_points

MOUNT_KEYS = {"mcp": "mcpIds", "skill": "skillIds", "tool": "toolIds", "model": "modelIds"}


def snake_to_camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in rest)


def tool_schema_dict(value: str | None) -> dict[str, Any]:
    """把库里的 JSON Schema 文本还原为对象，历史脏数据按空对象处理。"""
    if not (value or "").strip():
        return {}
    try:
        parsed = json.loads(value)
    except (json.JSONDecodeError, TypeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def row_dict(
    row: Any,
    *,
    rename: dict[str, str] | None = None,
    drop: set[str] | None = None,
    extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    values = {column.name: getattr(row, column.name) for column in row.__table__.columns}
    for key in drop or set():
        values.pop(key, None)
    mapping = rename or {}
    result = {mapping.get(key, snake_to_camel(key)): value for key, value in values.items()}
    if extra:
        result.update(extra)
    return result


async def build_workspace(session: AsyncSession) -> dict[str, Any]:
    config = await get_configs(session)
    limits = config["limits"]
    agent_rows = (await session.scalars(select(m.Agent))).all()
    mount_rows = (await session.scalars(select(m.AgentMount))).all()
    model_rows = (await session.scalars(select(m.ModelResource))).all()
    publication_rows = (await session.scalars(select(m.ModelPublication))).all()
    mcp_rows = (await session.scalars(select(m.McpServer))).all()
    mcp_tool_rows = (await session.scalars(select(m.McpTool))).all()
    skill_rows = (await session.scalars(select(m.Skill))).all()
    skill_version_rows = (await session.scalars(select(m.SkillVersion).order_by(m.SkillVersion.created_at.desc()))).all()
    tool_rows = (await session.scalars(select(m.BuiltinTool))).all()
    log_rows = (await session.scalars(
        select(m.LogEntry).order_by(m.LogEntry.timestamp.desc()).limit(int(limits["logSnapshotLimit"]))
    )).all()
    all_timestamps = (await session.scalars(select(m.LogEntry.timestamp))).all()
    notification_rows = (await session.scalars(select(m.Notification).order_by(m.Notification.timestamp.desc()))).all()
    export_rows = (await session.scalars(select(m.ExportTask).order_by(m.ExportTask.created_at.desc()))).all()
    chat_rows = (await session.scalars(select(m.ChatMessage).order_by(m.ChatMessage.timestamp.asc()))).all()
    settings_row = await session.get(m.WorkspaceSetting, 1)

    mounts_by_agent: dict[str, dict[str, list[str]]] = defaultdict(
        lambda: {"mcpIds": [], "skillIds": [], "toolIds": [], "modelIds": []}
    )
    for mount in mount_rows:
        mounts_by_agent[mount.agent_id][MOUNT_KEYS[mount.kind]].append(mount.resource_id)

    tools_by_server: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for tool in mcp_tool_rows:
        tools_by_server[tool.server_id].append(row_dict(
            tool,
            drop={"server_id", "input_schema"},
            extra={"inputSchema": tool_schema_dict(tool.input_schema)},
        ))

    versions_by_skill: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for version in skill_version_rows:
        versions_by_skill[version.skill_id].append(row_dict(version, drop={"id", "skill_id", "file_path", "manifest"}))

    # 被引用 Agent 数按挂载关系实时计算，避免库里 referenced_by 计数与实际挂载不一致。
    skill_refs: dict[str, int] = defaultdict(int)
    for mount in mount_rows:
        if mount.kind == "skill":
            skill_refs[mount.resource_id] += 1

    chat_sessions: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for message in chat_rows:
        chat_sessions[message.agent_id].append(row_dict(message, drop={"agent_id", "thread_id", "run_id"}))

    d30 = build_trend_points(list(all_timestamps), days=30)
    platform = config["platform"]
    settings = row_dict(settings_row, drop={"id", "updated_at"}) if settings_row else {
        "workspaceName": platform["defaultWorkspaceName"],
        "language": platform["defaultLanguage"],
        "timezone": platform["defaultTimezone"],
        "domain": platform["domain"],
        "telemetry": True,
        "autoArchive": True,
        "retentionDays": int(platform["defaultRetentionDays"]),
        "accessKeySuffix": "",
    }

    return {
        "agents": [row_dict(row, drop={"version"}, extra={"mounts": mounts_by_agent[row.id]}) for row in agent_rows],
        "models": [row_dict(row, rename={"headers_enc": "headers"}) for row in model_rows],
        "publications": [
            row_dict(row, drop={"headers_enc"}, extra={"headersConfigured": bool(row.headers_enc)})
            for row in publication_rows
        ],
        "mcps": [
            row_dict(row, rename={"headers_enc": "headers"}, extra={"tools": tools_by_server[row.id]})
            for row in mcp_rows
        ],
        "skills": [
            row_dict(
                row,
                extra={
                    "versions": versions_by_skill[row.id],
                    "referencedBy": skill_refs.get(row.id, 0),
                },
            )
            for row in skill_rows
        ],
        "tools": [row_dict(row) for row in tool_rows],
        "logs": [row_dict(row) for row in log_rows],
        "notifications": [row_dict(row) for row in notification_rows],
        "settings": settings,
        "systemConfig": config,
        "chatSessions": dict(chat_sessions),
        "trends": {"d14": d30[-14:], "d30": d30},
        "exportTasks": [
            row_dict(row, drop={"filters_json", "filters_label", "file_path"}, extra={"filters": row.filters_label})
            for row in export_rows
        ],
    }
