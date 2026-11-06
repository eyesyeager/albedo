"""集成测试固定基线。

只用于 pytest：每个用例开始前把业务表重置为一组确定的行，保证断言可重复。
这些行不属于产品代码、不写入 seed、不参与任何运行时链路。
"""

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.security import encrypt_secret, hash_secret, now_iso, suffix_of
from app.db import models as m
from app.db.seed import to_row
from app.db.seed_data import make_seed

RESET_TABLES = (
    m.AgentMount,
    m.McpTool,
    m.McpCallRecord,
    m.McpServer,
    m.SkillVersion,
    m.Skill,
    m.ModelPublication,
    m.ModelResource,
    m.Agent,
    m.Run,
    m.ChatMessage,
    m.LogEntry,
    m.ExportTask,
    m.Notification,
    m.TrendDaily,
    m.Session,
)

AGENTS = [
    ("agt-research", "深度研究助手", 1284, True, True, ["researchDepth", "outputLanguage"],
     "你是「深度研究助手」，研究深度：{{researchDepth}}，输出语言：{{outputLanguage}}。当前时间：{{currentTime}}"),
    ("agt-code", "代码审查专家", 0, True, False, [],
     "你是代码审查专家，当前时间：{{currentTime}}"),
    ("agt-data", "数据分析师", 0, False, False, [],
     "你是数据分析师，当前时间：{{currentTime}}"),
    ("agt-copy", "内容创作伙伴", 0, False, False, [],
     "你是内容创作伙伴，当前时间：{{currentTime}}"),
]

MODELS = [
    ("mdl-gpt", "GPT-4.1", "gpt-4.1", "chat"),
    ("mdl-deepseek", "DeepSeek V3.2", "deepseek-chat", "chat"),
    ("mdl-qwen", "Qwen3 Max", "qwen3-max", "chat"),
    ("mdl-embed", "Text Embedding 3", "text-embedding-3-large", "embedding"),
]

PUBLICATIONS = [
    ("pub-gpt-stable", "mdl-gpt", "v1.0.0", "albedo-gpt-4.1"),
    ("pub-gpt-creative", "mdl-gpt", "v2.0.0", "albedo-gpt-4.1-creative"),
    ("pub-deepseek", "mdl-deepseek", "v1.0.0", "deepseek-v3"),
]

MCPS = [
    ("mcp-tavily", "Tavily Search", "streamable_http"),
    ("mcp-github", "GitHub", "stdio"),
    ("mcp-pg", "PostgreSQL Analytics", "sse"),
]


def log_rows(stamp: str) -> list[dict]:
    common = {
        "timestamp": stamp, "status": "success", "durationMs": 120,
        "threadId": "thd_order_20261028_001", "resourceId": None,
        "inputSummary": None, "outputSummary": None, "tokenUsage": None,
        "errorCode": None, "errorMessage": None, "credentialSuffix": None,
    }
    rows: list[dict] = []
    types = ["session", "mcp", "tool", "model", "system", "skill",
             "session", "tool", "mcp", "model", "system", "skill"]
    for index in range(1, 13):
        log_id = f"log-{1000 + index}"
        row = dict(common)
        row.update({
            "logId": log_id,
            "type": types[index - 1],
            "source": "standard_api" if index in (1, 4) else "system",
            "name": "深度研究助手" if index == 1 else f"测试调用 {index}",
            "summary": f"深度研究助手执行链第 {index} 步" if index <= 3 else f"测试日志 {index}",
            "runId": "run_8fk2m1" if index <= 3 else None,
            "agentId": "agt-research" if index in (1, 4) else None,
            "durationMs": 120,
        })
        rows.append(row)
    return rows


async def reset_baseline(session: AsyncSession) -> None:
    for model in RESET_TABLES:
        await session.execute(delete(model))
    await session.execute(delete(m.Credential).where(m.Credential.type != "management_access_key"))
    await session.flush()

    stamp = now_iso()
    settings_row = await session.get(m.WorkspaceSetting, 1)
    if settings_row is None:
        session.add(m.WorkspaceSetting(id=1, workspace_name="集成测试工作区", updated_at=stamp))
    else:
        settings_row.access_key_suffix = "K9P4"
        settings_row.retention_days = 90
        settings_row.updated_at = stamp

    demo_key = get_settings().demo_access_key
    key_row = await session.scalar(select(m.Credential).where(m.Credential.type == "management_access_key"))
    if key_row is None:
        session.add(m.Credential(
            id="cred-management_access_key-workspace", type="management_access_key",
            hash=hash_secret(demo_key), cipher=encrypt_secret(demo_key),
            suffix=suffix_of(demo_key), resource_id="workspace", rotated_at=stamp, created_at=stamp,
        ))
    else:
        key_row.hash = hash_secret(demo_key)
        key_row.cipher = encrypt_secret(demo_key)
        key_row.suffix = suffix_of(demo_key)

    for model_id, name, upstream, model_type in MODELS:
        session.add(m.ModelResource(
            id=model_id, name=name, upstream_model_id=upstream, type=model_type,
            vendor="测试供应商", base_url="https://upstream.example.com/v1",
            credential_suffix="7890", headers_enc="", timeout_seconds=30,
            note="", enabled=True, connection="normal", latency_ms=120,
            last_tested_at=stamp, created_at=stamp, updated_at=stamp,
        ))

    for publication_id, model_id, version, identifier in PUBLICATIONS:
        session.add(m.ModelPublication(
            id=publication_id, model_id=model_id, version=version,
            call_identifier=identifier, credential_suffix="7890", headers_enc="",
            temperature=0.7, max_output_tokens=4096, qpm=60, enabled=True, created_at=stamp,
        ))

    for mcp_id, name, transport in MCPS:
        session.add(m.McpServer(
            id=mcp_id, name=name, description="测试用 MCP", transport=transport,
            endpoint="https://mcp.example.com/mcp", headers_enc="", timeout_seconds=30,
            qpm=60, max_concurrency=5, enabled=True, connection="normal",
            call_count=0, success_rate=0.0, last_tested_at=stamp,
            created_at=stamp, updated_at=stamp,
        ))

    for agent_id, name, session_count, api_enabled, agui_enabled, variables, prompt in AGENTS:
        session.add(m.Agent(
            id=agent_id, name=name, description=f"{name}（测试基线）", theme="violet",
            default_model_id="mdl-gpt", test_model_id="mdl-gpt", system_prompt=prompt,
            variables=variables,
            model_params={"temperature": 0.7, "topP": 0.9, "maxOutputTokens": 4096},
            advanced={"keepContext": True, "autoRetry": True, "outputThinkingSummary": False,
                      "maxToolCalls": 10, "maxRunSeconds": 120},
            api_enabled=api_enabled, agui_enabled=agui_enabled,
            credential_suffix="7K2m", session_count=session_count, version=1,
            created_at=stamp, updated_at=stamp,
        ))
        session.add(m.AgentMount(agent_id=agent_id, kind="model", resource_id="mdl-gpt"))
    session.add(m.AgentMount(agent_id="agt-research", kind="tool", resource_id="tool-web_search"))

    for row in log_rows(stamp):
        session.add(m.LogEntry(**{
            "log_id": row["logId"], "timestamp": row["timestamp"], "type": row["type"],
            "status": row["status"], "source": row["source"], "name": row["name"],
            "summary": row["summary"], "run_id": row["runId"], "thread_id": row["threadId"],
            "agent_id": row["agentId"], "resource_id": row["resourceId"],
            "duration_ms": row["durationMs"], "input_summary": row["inputSummary"],
            "output_summary": row["outputSummary"], "token_usage": row["tokenUsage"],
            "error_code": row["errorCode"], "error_message": row["errorMessage"],
            "credential_suffix": row["credentialSuffix"],
        }))

    await session.execute(delete(m.BuiltinTool))
    await session.flush()
    for tool in make_seed()["tools"]:
        session.add(m.BuiltinTool(**to_row(tool, m.BuiltinTool)))
    await session.commit()


async def cleanup(session: AsyncSession) -> None:
    """用例结束后恢复干净初始态，避免测试数据留在开发库。"""
    for model in RESET_TABLES:
        await session.execute(delete(model))
    await session.execute(delete(m.Credential).where(m.Credential.type != "management_access_key"))

    stamp = now_iso()
    seed_settings = make_seed()["settings"]
    settings_row = await session.get(m.WorkspaceSetting, 1)
    if settings_row is not None:
        settings_row.workspace_name = seed_settings["workspaceName"]
        settings_row.language = seed_settings["language"]
        settings_row.timezone = seed_settings["timezone"]
        settings_row.domain = seed_settings["domain"]
        settings_row.retention_days = seed_settings["retentionDays"]
        settings_row.access_key_suffix = seed_settings["accessKeySuffix"]
        settings_row.updated_at = stamp

    demo_key = get_settings().demo_access_key
    key_row = await session.scalar(select(m.Credential).where(m.Credential.type == "management_access_key"))
    if key_row is not None:
        key_row.hash = hash_secret(demo_key)
        key_row.cipher = encrypt_secret(demo_key)
        key_row.suffix = suffix_of(demo_key)

    for tool in make_seed()["tools"]:
        row = await session.get(m.BuiltinTool, tool["id"])
        if row is not None:
            row.call_count = 0
            row.enabled = True
    await session.commit()
