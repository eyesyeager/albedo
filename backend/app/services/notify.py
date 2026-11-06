"""系统通知：由真实业务事件写入，不再预置演示通知。"""

from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.logging import sanitize
from app.core.security import new_id, now_iso
from app.db import models as m


async def notify(
    session: AsyncSession,
    *,
    kind: str,
    title: str,
    summary: str,
    target_path: str = "",
) -> dict[str, Any]:
    row = m.Notification(
        id=new_id("ntf-"),
        kind=kind,
        title=title,
        summary=sanitize(summary)[:1000],
        timestamp=now_iso(),
        target_path=target_path,
        read=False,
    )
    session.add(row)
    await session.flush()
    return {
        "id": row.id, "kind": row.kind, "title": row.title, "summary": row.summary,
        "timestamp": row.timestamp, "targetPath": row.target_path, "read": row.read,
    }
