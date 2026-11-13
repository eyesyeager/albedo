import zipfile

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import decrypt_secret
from app.db import models as m
from app.services.agent_rules import load_mounts, load_skill_mount_versions
from app.services.common import agent_to_dict, model_to_dict, read_headers
from app.services.skill_package import resource_members, script_members
from app.services.skill_storage import materialize_async
from app.services.system_config import limit as config_limit

_SENSITIVE_KEYS = {"upstream", "headers", "env", "apiKey", "cipher", "hash"}


async def build_runtime_snapshot(session: AsyncSession, agent: m.Agent, model: m.ModelResource) -> dict:
    """冻结本次运行所需的配置：Prompt、生成参数、挂载资源与已解析工具。

    进入 LangGraph 后不再读库，运行途中保存的新配置只影响后续 Run。
    """
    snapshot = await agent_to_dict(session, agent)
    snapshot["model"] = model_to_dict(model)
    # 推理必须使用智能体实际配置的模型资源，而不是全局 LLM_* 兜底：
    # 否则停用/更换默认模型、配置上游地址与自定义头都不会生效，日志也与实际调用不符。
    upstream_credential = await session.scalar(
        select(m.Credential).where(
            m.Credential.type == "upstream_model_key",
            m.Credential.resource_id == model.id,
        )
    )
    snapshot["upstream"] = {
        "apiKey": (
            decrypt_secret(upstream_credential.cipher)
            if upstream_credential and upstream_credential.cipher else ""
        ) or "",
        "baseUrl": model.base_url,
        "model": model.upstream_model_id,
        "headers": read_headers(model.headers_enc),
    }
    mounts = await load_mounts(session, agent.id)
    mount_versions = await load_skill_mount_versions(session, agent.id)
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
            "env": read_headers(server.env_enc),
            "timeoutSeconds": server.timeout_seconds,
            "qpm": server.qpm,
            "maxConcurrency": server.max_concurrency,
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

    skill_instruction_limit = int(config_limit("skillBodyMaxChars", 8000)) if mounts["skillIds"] else 0
    for skill_id in mounts["skillIds"]:
        skill = await session.get(m.Skill, skill_id)
        if skill is None:
            continue
        version = mount_versions.get(skill_id) or skill.version
        version_row = (
            await session.scalars(
                select(m.SkillVersion).where(
                    m.SkillVersion.skill_id == skill_id, m.SkillVersion.version == version
                )
            )
        ).first()
        instructions = (version_row.instructions if version_row else "") or skill.instructions
        package_path = None
        if version_row:
            # COS 为权威存储；缓存未命中时异步下载，不阻塞事件循环。
            package_path = await materialize_async(
                version_row.package_key or "", legacy_path=version_row.file_path or ""
            )
        try:
            resources = resource_members(package_path)
            scripts = script_members(package_path)
        except (OSError, zipfile.BadZipFile, ValueError):
            resources, scripts = [], []
        resolved["skill"].append({
            "id": skill.id,
            "name": skill.name,
            # L1：触发面，只以短清单形式进 system prompt。
            "description": (skill.trigger_description or skill.description or "")[:1024],
            # L2：正文留在服务端快照内存，命中时才注入上下文。
            "instructions": instructions,
            "instructionLimit": skill_instruction_limit,
            "version": version,
            # L3：包定位与按需可用的成员清单。
            "packagePath": str(package_path) if package_path else "",
            "entry": version_row.entry if version_row else "",
            "resources": resources[:40],
            "scripts": scripts[:40],
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
    snapshot["skillCatalog"] = [
        {
            "id": item["id"],
            "name": item["name"],
            "description": item["description"],
            "version": item["version"],
        }
        for item in resolved["skill"]
    ]
    return snapshot


def safe_snapshot(snapshot: dict) -> dict:
    """落库前剔除解密后的凭据与请求头，避免明文进入 runs.config_snapshot。"""
    cleaned = {key: value for key, value in snapshot.items() if key not in _SENSITIVE_KEYS}
    model = cleaned.get("model")
    if isinstance(model, dict):
        cleaned["model"] = {k: v for k, v in model.items() if k not in _SENSITIVE_KEYS}
    resolved = cleaned.get("resolvedMounts")
    if isinstance(resolved, dict):
        cleaned["resolvedMounts"] = {
            group: [
                {k: v for k, v in item.items() if k not in _SENSITIVE_KEYS}
                if isinstance(item, dict) else item
                for item in (items if isinstance(items, list) else [])
            ]
            for group, items in resolved.items()
        }
    return cleaned
