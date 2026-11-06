"""真实工具调用落库：内置工具调用次数、MCP 调用记录与成功率。"""

import logging

from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import now_iso
from app.db import models as m
from app.db.session import SessionLocal

logger = logging.getLogger("albedo.runtime.usage")


async def record_tool_call(
    session: AsyncSession,
    *,
    kind: str,
    resource_id: str,
    tool_key: str,
    status: str,
    duration_ms: int,
    run_id: str,
) -> None:
    """在调用方事务内写入真实调用统计。"""
    if not resource_id:
        return
    if kind == "tool":
        await session.execute(
            update(m.BuiltinTool)
            .where(m.BuiltinTool.id == resource_id)
            .values(call_count=m.BuiltinTool.call_count + 1)
        )
        return
    if kind != "mcp":
        return
    session.add(m.McpCallRecord(
        server_id=resource_id,
        tool_key=tool_key,
        run_id=run_id,
        status=status,
        duration_ms=duration_ms,
        timestamp=now_iso(),
    ))
    await session.flush()
    total = int((await session.scalar(
        select(func.count()).select_from(m.McpCallRecord).where(m.McpCallRecord.server_id == resource_id)
    )) or 0)
    succeeded = int((await session.scalar(
        select(func.count()).select_from(m.McpCallRecord).where(
            m.McpCallRecord.server_id == resource_id, m.McpCallRecord.status == "success"
        )
    )) or 0)
    await session.execute(
        update(m.McpServer)
        .where(m.McpServer.id == resource_id)
        .values(call_count=total, success_rate=round(succeeded / total * 100, 2) if total else 0.0)
    )


async def record_in_new_session(**kwargs) -> None:
    """图节点没有请求级 session，使用独立短事务记录，失败只记日志不影响运行。"""
    try:
        async with SessionLocal() as session:
            await record_tool_call(session, **kwargs)
            await session.commit()
    except Exception as exc:  # noqa: BLE001
        logger.warning("工具调用统计写入失败：%s", exc)
