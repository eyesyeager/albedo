"""真实工具调用落库：内置工具调用次数、MCP 调用记录与成功率。"""

import logging

from sqlalchemy import func, update
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
    # 原子增量 + SQL 侧加权平均：并发调用不再互相覆盖计数，也避免两次全表 COUNT。
    success_value = 100.0 if status == "success" else 0.0
    await session.execute(
        update(m.McpServer)
        .where(m.McpServer.id == resource_id)
        .values(
            call_count=m.McpServer.call_count + 1,
            success_rate=func.round(
                (m.McpServer.success_rate * m.McpServer.call_count + success_value)
                / (m.McpServer.call_count + 1),
                2,
            ),
        )
    )


async def record_in_new_session(**kwargs) -> None:
    """图节点没有请求级 session，使用独立短事务记录，失败只记日志不影响运行。"""
    try:
        async with SessionLocal() as session:
            await record_tool_call(session, **kwargs)
            await session.commit()
    except Exception as exc:  # noqa: BLE001
        logger.warning("工具调用统计写入失败：%s", exc)


async def record_skill_load(**kwargs) -> None:
    """L1/L2/L3 分层加载与执行记录，关联 runId / threadId / 版本号。"""
    try:
        async with SessionLocal() as session:
            session.add(m.SkillLoadRecord(timestamp=now_iso(), **kwargs))
            await session.commit()
    except Exception as exc:  # noqa: BLE001
        logger.warning("Skill 分层加载记录写入失败：%s", exc)
