from fastapi import APIRouter, Depends
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_session
from app.db import models as m
from app.db.session import get_db
from app.schemas.domain import NotificationItem
from app.schemas.responses import WriteResponse
from app.services.audit import audit
from app.services.snapshot import row_dict

router = APIRouter(tags=["notifications"])


@router.get("/notifications", response_model=list[NotificationItem], summary="通知列表")
async def list_notifications(
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> list[dict]:
    rows = (await db.scalars(select(m.Notification).order_by(m.Notification.timestamp.desc()))).all()
    return [row_dict(row) for row in rows]


@router.post("/notifications/read-all", response_model=WriteResponse, summary="全部标记已读")
async def read_all(
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    await db.execute(update(m.Notification).values(read=True))
    await db.flush()
    rows = (await db.scalars(select(m.Notification).order_by(m.Notification.timestamp.desc()))).all()
    log = await audit(db, "通知全部已读", "全部通知已标记为已读，刷新后保持")
    await db.commit()
    return {"notifications": [row_dict(row) for row in rows], "log": log}
