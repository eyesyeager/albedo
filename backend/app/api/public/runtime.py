from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiException
from app.core.security import new_id, now_iso
from app.db import models as m
from app.db.seed import to_row
from app.runtime import RuntimeRequest, RuntimeResult, get_runtime_engine
from app.services.runtime_snapshot import build_runtime_snapshot

ERROR_MAP = {
    "run_timeout": ("agent_run_timeout", 504),
    "missing_variable": ("missing_variable", 422),
    "rate_limit_exceeded": ("rate_limit_exceeded", 429),
    "upstream_timeout": ("agent_run_failed", 500),
    "upstream_unavailable": ("agent_run_failed", 500),
    "upstream_auth_failed": ("agent_run_failed", 500),
}


async def prepare_agent_run(
    db: AsyncSession,
    agent: m.Agent,
    model: m.ModelResource,
    *,
    content: str,
    variables: dict[str, str] | None = None,
    thread_id: str | None = None,
    run_id: str | None = None,
    source: str,
) -> tuple[RuntimeRequest, dict]:
    if not content.strip():
        raise ApiException("invalid_request", "messages 至少需要一条非空 content", 400)
    if run_id and await db.get(m.Run, run_id):
        raise ApiException("duplicate_run_id", "runId 已存在，请更换后重试", 409)
    if not model.enabled:
        raise ApiException("agent_not_published", "默认模型已停用，智能体不可调用", 409)
    if model.connection != "normal":
        raise ApiException("agent_not_published", "默认模型连接状态异常，智能体不可调用", 409)

    snapshot = await build_runtime_snapshot(db, agent, model)
    return RuntimeRequest(
        agent_id=agent.id,
        thread_id=thread_id or new_id("thd_", 8),
        run_id=run_id or new_id("run_", 8),
        content=content,
        snapshot=snapshot,
        variables=variables or {},
        source=source,
        strict_variables=True,
    ), snapshot


async def persist_result(
    db: AsyncSession,
    agent: m.Agent,
    result: RuntimeResult,
    snapshot: dict,
    source: str,
) -> None:
    if result.finish_reason in ("stop", "tool_limit"):
        status = "completed"
    elif result.finish_reason == "timeout":
        status = "timeout"
    else:
        status = "failed"

    for message in result.messages:
        db.add(m.ChatMessage(**{
            "id": message["id"],
            "agent_id": agent.id,
            "thread_id": result.thread_id,
            "run_id": result.run_id,
            "role": message["role"],
            "content": message["content"],
            "tool_call": message.get("toolCall"),
            "timestamp": message["timestamp"],
        }))
    for log in result.logs:
        db.add(m.LogEntry(**to_row(log, m.LogEntry)))
    db.add(m.Run(
        run_id=result.run_id,
        thread_id=result.thread_id,
        agent_id=agent.id,
        source=source,
        status=status,
        config_snapshot=snapshot,
        finish_reason=result.finish_reason,
        usage=result.usage,
        duration_ms=result.duration_ms,
        created_at=now_iso(),
        updated_at=now_iso(),
    ))
    agent.session_count += 1
    await db.commit()


async def execute_agent_run(
    db: AsyncSession,
    agent: m.Agent,
    model: m.ModelResource,
    *,
    content: str,
    variables: dict[str, str] | None = None,
    thread_id: str | None = None,
    run_id: str | None = None,
    source: str,
) -> RuntimeResult:
    request, snapshot = await prepare_agent_run(
        db, agent, model,
        content=content,
        variables=variables,
        thread_id=thread_id,
        run_id=run_id,
        source=source,
    )
    try:
        result = await get_runtime_engine().run(request)
    except ApiException as exc:
        code, status = ERROR_MAP.get(exc.code, ("agent_run_failed", 500))
        raise ApiException(code, exc.message, status) from exc
    await persist_result(db, agent, result, snapshot, source)
    return result
