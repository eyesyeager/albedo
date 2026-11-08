import re
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiException
from app.db import models as m
from app.services.system_config import limit as config_limit

VARIABLE_PATTERN = re.compile(r"{{\s*([A-Za-z_][A-Za-z0-9_]*)\s*}}")
SYSTEM_VARIABLES = {"currentTime"}
VARIABLE_NAME_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def extract_variables(prompt: str) -> list[str]:
    return sorted({name for name in VARIABLE_PATTERN.findall(prompt or "") if name not in SYSTEM_VARIABLES})


def validate_variables(variables: list[str]) -> None:
    for name in variables:
        if not VARIABLE_NAME_PATTERN.match(name):
            raise ApiException("validation_error", f"变量名「{name}」不合法，需以字母或下划线开头", 400)


async def load_mounts(session: AsyncSession, agent_id: str) -> dict[str, list[str]]:
    rows = (await session.scalars(select(m.AgentMount).where(m.AgentMount.agent_id == agent_id))).all()
    keys = {"mcp": "mcpIds", "skill": "skillIds", "tool": "toolIds", "model": "modelIds"}
    grouped: dict[str, list[str]] = {"mcpIds": [], "skillIds": [], "toolIds": [], "modelIds": []}
    for row in rows:
        grouped[keys[row.kind]].append(row.resource_id)
    return grouped


async def load_skill_mount_versions(session: AsyncSession, agent_id: str) -> dict[str, str]:
    """读取 Agent 挂载的 Skill 版本；空值代表旧数据，运行时回退为跟随当前版本。"""
    rows = (
        await session.scalars(
            select(m.AgentMount).where(m.AgentMount.agent_id == agent_id, m.AgentMount.kind == "skill")
        )
    ).all()
    return {row.resource_id: row.skill_version for row in rows if row.skill_version}


async def replace_mounts(session: AsyncSession, agent_id: str, mounts: dict[str, list[str]]) -> None:
    existing = (await session.scalars(select(m.AgentMount).where(m.AgentMount.agent_id == agent_id))).all()
    for row in existing:
        await session.delete(row)
    keys = {"mcpIds": "mcp", "skillIds": "skill", "toolIds": "tool", "modelIds": "model"}
    await session.flush()
    skill_versions: dict[str, str] = {}
    if mounts.get("skillIds"):
        rows = (await session.scalars(select(m.Skill).where(m.Skill.id.in_(mounts["skillIds"])))).all()
        skill_versions = {row.id: row.version for row in rows}
    for key, kind in keys.items():
        for resource_id in mounts.get(key, []):
            session.add(m.AgentMount(
                agent_id=agent_id,
                kind=kind,
                resource_id=resource_id,
                # 挂载时固化版本：后续上传新版本不会改变已有 Agent 的行为。
                skill_version=skill_versions.get(resource_id, "") if kind == "skill" else "",
            ))


async def publish_issues(session: AsyncSession, agent: m.Agent) -> list[str]:
    issues: list[str] = []
    if not agent.name.strip():
        issues.append("智能体名称未配置")
    if not agent.system_prompt.strip():
        issues.append("System Prompt 不能为空")

    default = await session.get(m.ModelResource, agent.default_model_id) if agent.default_model_id else None
    if default is None:
        issues.append("默认模型未配置")
    elif not default.enabled:
        issues.append(f"默认模型「{default.name}」已停用")
    elif default.connection != "normal":
        issues.append(f"默认模型「{default.name}」连接状态异常，请重新测试连接")

    mounts = await load_mounts(session, agent.id)
    for model_id in mounts["modelIds"]:
        model = await session.get(m.ModelResource, model_id)
        if model is None:
            issues.append(f"模型资源已丢失（{model_id}）")
        elif not model.enabled:
            issues.append(f"模型「{model.name}」已停用")
        elif model.connection != "normal":
            issues.append(f"模型「{model.name}」连接状态异常")
    for mcp_id in mounts["mcpIds"]:
        mcp = await session.get(m.McpServer, mcp_id)
        if mcp is None:
            issues.append(f"MCP 资源已丢失（{mcp_id}）")
        elif not mcp.enabled:
            issues.append(f"MCP「{mcp.name}」已停用")
    for skill_id in mounts["skillIds"]:
        if await session.get(m.Skill, skill_id) is None:
            issues.append(f"Skill 资源已丢失（{skill_id}）")
    for tool_id in mounts["toolIds"]:
        tool = await session.get(m.BuiltinTool, tool_id)
        if tool is None:
            issues.append(f"内置工具资源已丢失（{tool_id}）")
        elif not tool.enabled:
            issues.append(f"内置工具「{tool.name}」已停用")

    advanced: dict[str, Any] = agent.advanced or {}
    max_tool_calls = advanced.get("maxToolCalls", config_limit("maxToolCallsMin", 1))
    max_run_seconds = advanced.get("maxRunSeconds", config_limit("maxRunSecondsMin", 3))
    calls_min, calls_max = int(config_limit("maxToolCallsMin", 1)), int(config_limit("maxToolCallsMax", 100))
    seconds_min, seconds_max = int(config_limit("maxRunSecondsMin", 3)), int(config_limit("maxRunSecondsMax", 3600))
    if not isinstance(max_tool_calls, int) or not calls_min <= max_tool_calls <= calls_max:
        issues.append(f"最大工具调用次数需在 {calls_min} ~ {calls_max} 之间")
    if not isinstance(max_run_seconds, int) or not seconds_min <= max_run_seconds <= seconds_max:
        issues.append(f"单次运行最大时长需在 {seconds_min} ~ {seconds_max} 之间")
    return issues
