from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import models as m
from app.services.agent_rules import load_mounts
from app.services.common import agent_to_dict, model_to_dict, read_headers


async def build_runtime_snapshot(session: AsyncSession, agent: m.Agent, model: m.ModelResource) -> dict:
    """冻结本次运行所需的配置：Prompt、生成参数、挂载资源与已解析工具。

    进入 LangGraph 后不再读库，运行途中保存的新配置只影响后续 Run。
    """
    snapshot = await agent_to_dict(session, agent)
    snapshot["model"] = model_to_dict(model)
    mounts = await load_mounts(session, agent.id)
    resolved: dict[str, list[dict]] = {"mcp": [], "skill": [], "tool": [], "model": []}

    for mcp_id in mounts["mcpIds"]:
        server = await session.get(m.McpServer, mcp_id)
        if server is None or not server.enabled:
            continue
        tools = (
            await session.scalars(
                select(m.McpTool).where(m.McpTool.server_id == mcp_id, m.McpTool.enabled.is_(True))
            )
        ).all()
        resolved["mcp"].append({
            "id": server.id,
            "name": server.name,
            "enabled": server.enabled,
            "connection": server.connection,
            "transport": server.transport,
            "endpoint": server.endpoint,
            "headers": read_headers(server.headers_enc),
            "tools": [
                {"name": tool.name, "key": tool.key, "description": tool.description, "enabled": tool.enabled}
                for tool in tools
            ],
        })

    for tool_id in mounts["toolIds"]:
        tool = await session.get(m.BuiltinTool, tool_id)
        if tool is None:
            continue
        resolved["tool"].append({
            "id": tool.id,
            "name": tool.name,
            "key": tool.key,
            "description": tool.description,
            "enabled": tool.enabled,
        })

    for skill_id in mounts["skillIds"]:
        skill = await session.get(m.Skill, skill_id)
        if skill is None:
            continue
        resolved["skill"].append({
            "id": skill.id,
            "name": skill.name,
            "description": skill.description,
            "instructions": skill.instructions,
            "version": skill.version,
        })

    for model_id in mounts["modelIds"]:
        item = await session.get(m.ModelResource, model_id)
        if item is None:
            continue
        resolved["model"].append({
            "id": item.id,
            "name": item.name,
            "enabled": item.enabled,
            "connection": item.connection,
        })

    snapshot["resolvedMounts"] = resolved
    return snapshot
