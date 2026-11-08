from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.logging import sanitize
from app.core.security import new_id, now_iso
from app.db import models as m
from app.db.seed import to_row
from app.schemas.domain import normalize_log_type
from app.services.notify import notify


def make_log_entry(
    name: str,
    summary: str,
    *,
    resource_id: str | None = None,
    status: str = "success",
    log_type: str = "system",
    source: str = "system",
    **extra: Any,
) -> dict[str, Any]:
    # 系统操作日志没有可测量的耗时，保持未知（null），不再写入伪造的 120ms。
    duration_ms = extra.pop("durationMs", None)
    entry: dict[str, Any] = {
        "logId": new_id("log-"),
        "timestamp": now_iso(),
        "type": log_type,
        "status": status,
        "source": source,
        "name": name,
        "summary": sanitize(summary),
        "resourceId": resource_id,
        "durationMs": duration_ms,
    }
    entry.update({key: sanitize(value) for key, value in extra.items()})
    # 工具 spec.kind（如 skill_resource / skill_script）不是合法的日志类型，
    # 统一在此收口，保证任何写入 log_entries 的 type 都能通过响应模型校验。
    entry["type"] = normalize_log_type(entry.get("type"), default="system")
    return entry


async def write_log(session: AsyncSession, entry: dict[str, Any]) -> dict[str, Any]:
    session.add(m.LogEntry(**to_row(entry, m.LogEntry)))
    await session.flush()
    return entry


async def audit(
    session: AsyncSession,
    name: str,
    summary: str,
    *,
    resource_id: str | None = None,
    status: str = "success",
) -> dict[str, Any]:
    entry = await write_log(session, make_log_entry(name, summary, resource_id=resource_id, status=status))
    if status != "success":
        await notify(session, kind="error", title=name, summary=summary, target_path="/logs")
    return entry
