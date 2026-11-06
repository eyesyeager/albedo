from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_session
from app.core.security import now_iso
from app.db import models as m
from app.db.session import get_db
from app.schemas.domain import BuiltinTool
from app.schemas.requests import EnabledPatch
from app.schemas.responses import WriteResponse
from app.services.common import commit_write, get_or_404, tool_to_dict

router = APIRouter(tags=["tools"])


@router.get("/tools", response_model=list[BuiltinTool], summary="内置工具列表")
async def list_tools(
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> list[dict]:
    rows = (await db.scalars(select(m.BuiltinTool))).all()
    return [tool_to_dict(row) for row in rows]


@router.patch("/tools/{tool_id}/enabled", response_model=WriteResponse, summary="启停内置工具")
async def toggle_tool(
    tool_id: str,
    payload: EnabledPatch,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.BuiltinTool, tool_id)
    row.enabled = payload.enabled
    await db.flush()
    entity = tool_to_dict(row)
    return await commit_write(
        db, entity, "启用内置工具" if payload.enabled else "停用内置工具",
        f"{row.name} 已{'启用' if payload.enabled else '停用'}，更新于 {now_iso()}", resource_id=tool_id,
    )
