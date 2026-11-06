from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_session
from app.db import models as m
from app.db.session import get_db
from app.schemas.domain import WorkspaceData
from app.services.snapshot import build_workspace

router = APIRouter(tags=["workspace"])


@router.get("/workspace/bootstrap", response_model=WorkspaceData, summary="工作区全量快照")
async def bootstrap(
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    return await build_workspace(db)
