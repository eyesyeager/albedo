from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_session
from app.core.errors import ApiException
from app.db import models as m
from app.db.session import get_db
from app.schemas.domain import ImpactResult
from app.services.common import agent_to_dict

router = APIRouter(tags=["resources"])

VALID_KINDS = {"model", "mcp", "tool", "skill"}


@router.get("/resources/{kind}/{resource_id}/impact", response_model=ImpactResult, summary="资源停用影响分析")
async def resource_impact(
    kind: str,
    resource_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    if kind not in VALID_KINDS:
        raise ApiException("validation_error", "资源类型必须是 model / mcp / tool / skill", 400)
    mounts = (
        await db.scalars(
            select(m.AgentMount).where(m.AgentMount.kind == kind, m.AgentMount.resource_id == resource_id)
        )
    ).all()
    agent_ids = list(dict.fromkeys(mount.agent_id for mount in mounts))
    agents = []
    for agent_id in agent_ids:
        row = await db.get(m.Agent, agent_id)
        if row is not None:
            agents.append(await agent_to_dict(db, row))
    return {"agents": agents, "names": [item["name"] for item in agents]}
