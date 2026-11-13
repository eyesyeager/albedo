from fastapi import Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiException
from app.core.redis_client import try_increment
from app.core.security import hash_secret, suffix_of, verify_secret
from app.db import models as m

# 未命中 suffix 时仍做一次固定成本的 Argon2 校验，抹平命中/未命中的时序差异；
# 不再遍历同类全部凭据，避免随机 Token 把一次请求放大成 N 次重型哈希。
_DUMMY_HASH = hash_secret("invalid-placeholder-key")
_AUTH_FAILURE_WINDOW = 60
_AUTH_FAILURE_LIMIT = 10


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
    if not candidates:
        verify_secret(_DUMMY_HASH, token)
        return None
    for row in candidates:
        if verify_secret(row.hash, token):
            return row
    return None


async def _note_auth_failure(request: Request, kind: str) -> None:
    origin = request.client.host if request.client else "unknown"
    count = await try_increment(f"albedo:authfail:{kind}:{origin}", _AUTH_FAILURE_WINDOW)
    if count > _AUTH_FAILURE_LIMIT:
        raise ApiException("rate_limit_exceeded", "认证失败次数过多，请稍后再试", 429)


async def require_agent(request: Request, db: AsyncSession) -> tuple[m.Agent, m.Credential]:
    token = bearer_token(request)
    credential = await match_credential(db, "agent_token", token)
    if credential is None:
        await _note_auth_failure(request, "agent_token")
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
        await _note_auth_failure(request, "model_publication_key")
        raise ApiException("invalid_api_key", "模型发布 Key 无效或已失效", 401)
    publication = await db.get(m.ModelPublication, credential.resource_id)
    if publication is None:
        raise ApiException("model_not_found", "调用标识不存在或版本已删除", 404)
    model = await db.get(m.ModelResource, publication.model_id)
    if model is None:
        raise ApiException("model_not_found", "模型资源不存在", 404)
    return publication, model, credential
