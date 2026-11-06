from fastapi import Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiException
from app.core.security import suffix_of, verify_secret
from app.db import models as m


def bearer_token(request: Request) -> str:
    header = request.headers.get("Authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise ApiException("invalid_api_key", "缺少 Authorization: Bearer <Key>", 401)
    return token.strip()


async def match_credential(db: AsyncSession, kind: str, token: str) -> m.Credential | None:
    suffix = suffix_of(token)
    candidates = (
        await db.scalars(select(m.Credential).where(m.Credential.type == kind, m.Credential.suffix == suffix))
    ).all()
    seen = set()
    for row in candidates:
        seen.add(row.id)
        if verify_secret(row.hash, token):
            return row
    for row in (await db.scalars(select(m.Credential).where(m.Credential.type == kind))).all():
        if row.id in seen:
            continue
        if verify_secret(row.hash, token):
            return row
    return None


async def require_agent(request: Request, db: AsyncSession) -> tuple[m.Agent, m.Credential]:
    token = bearer_token(request)
    credential = await match_credential(db, "agent_token", token)
    if credential is None:
        raise ApiException("invalid_api_key", "Agent Token 无效或已失效", 401)
    agent = await db.get(m.Agent, credential.resource_id)
    if agent is None:
        raise ApiException("agent_not_found", "智能体不存在或已删除", 404)
    return agent, credential


async def require_publication(
    request: Request, db: AsyncSession
) -> tuple[m.ModelPublication, m.ModelResource, m.Credential]:
    token = bearer_token(request)
    credential = await match_credential(db, "model_publication_key", token)
    if credential is None:
        raise ApiException("invalid_api_key", "模型发布 Key 无效或已失效", 401)
    publication = await db.get(m.ModelPublication, credential.resource_id)
    if publication is None:
        raise ApiException("model_not_found", "调用标识不存在或版本已删除", 404)
    model = await db.get(m.ModelResource, publication.model_id)
    if model is None:
        raise ApiException("model_not_found", "模型资源不存在", 404)
    return publication, model, credential
