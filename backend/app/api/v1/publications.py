import re
from typing import Any

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_session
from app.api.v1.models import require_connection_normal
from app.core.errors import ApiException
from app.core.security import new_id, now_iso, suffix_of
from app.db import models as m
from app.db.seed import to_row
from app.db.session import get_db
from app.schemas.domain import ModelPublication
from app.schemas.requests import EnabledPatch, PublicationDraft, PublicationPatch
from app.schemas.responses import WriteResponse
from app.services.common import (
    commit_write,
    get_or_404,
    publication_to_dict,
    store_credential,
    validate_json_object,
    write_headers,
)
from app.services.notify import notify
from app.services.system_config import get_configs

router = APIRouter(tags=["publications"])

IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9._-]+$")


async def validate_draft(db: AsyncSession, draft: PublicationDraft, model_id: str) -> tuple[str, str, dict[str, Any]]:
    version = draft.version.strip()
    identifier = draft.callIdentifier.strip()
    if not version:
        raise ApiException("validation_error", "版本号不能为空", 400)
    existing = (
        await db.scalars(select(m.ModelPublication).where(m.ModelPublication.model_id == model_id, m.ModelPublication.version == version))
    ).first()
    if existing:
        raise ApiException("duplicate_identifier", "该版本号已存在，请使用新的版本号", 409)
    if not identifier:
        raise ApiException("validation_error", "模型调用标识不能为空", 400)
    if not IDENTIFIER_PATTERN.fullmatch(identifier):
        raise ApiException("validation_error", "调用标识仅允许字母、数字、点、下划线和连字符", 400)
    duplicate = (
        await db.scalars(select(m.ModelPublication).where(m.ModelPublication.call_identifier == identifier))
    ).first()
    if duplicate:
        raise ApiException("duplicate_identifier", "模型调用标识已被占用，请更换", 409)
    full_key = draft.apiKey.strip()
    if len(full_key) < 12:
        raise ApiException("validation_error", "自定义 API Key 至少需要 12 个字符", 400)
    headers = validate_json_object(draft.headers)
    if not 0 <= draft.temperature <= 2:
        raise ApiException("validation_error", "温度需在 0 ~ 2 之间", 400)
    if draft.maxOutputTokens < 1:
        raise ApiException("validation_error", "最大输出 Tokens 必须是正整数", 400)
    if draft.qpm < 1:
        raise ApiException("validation_error", "QPM 必须是正整数", 400)
    return version, identifier, {"fullKey": full_key, "headers": headers}


@router.get("/models/{model_id}/publications", response_model=list[ModelPublication], summary="模型发布版本列表")
async def list_publications(
    model_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> list[dict]:
    rows = (await db.scalars(select(m.ModelPublication).where(m.ModelPublication.model_id == model_id))).all()
    return [publication_to_dict(row) for row in rows]


@router.post("/models/{model_id}/publications", response_model=WriteResponse, status_code=201, summary="发布模型版本")
async def publish_model(
    model_id: str,
    payload: PublicationDraft,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    model = await get_or_404(db, m.ModelResource, model_id)
    require_connection_normal(model)
    config = await get_configs(db)
    allowed = config["options"]["publishableModelTypes"]
    if model.type not in allowed:
        raise ApiException("validation_error", f"仅支持 {', '.join(allowed)} 类型模型创建发布版本", 400)
    version, identifier, values = await validate_draft(db, payload, model_id)
    full_key = values["fullKey"]
    headers_enc = write_headers(payload.headers) if values["headers"] else ""
    publication_id = new_id("pub-")
    row = m.ModelPublication(
        id=publication_id,
        model_id=model_id,
        version=version,
        call_identifier=identifier,
        credential_suffix=suffix_of(full_key),
        headers_enc=headers_enc,
        temperature=payload.temperature,
        max_output_tokens=payload.maxOutputTokens,
        qpm=payload.qpm,
        enabled=True,
        created_at=now_iso(),
    )
    db.add(row)
    await store_credential(db, "model_publication_key", publication_id, full_key, row.credential_suffix)
    await notify(db, kind="success", title="模型发布版本已生效", summary=f"{model.name} 发布 {version}，调用标识 {identifier}", target_path="/resources/models")
    entity = publication_to_dict(row)
    return await commit_write(
        db, None, "发布模型版本",
        f"{model.name} 发布 {version}，调用标识 {identifier}",
        resource_id=model_id, publication=entity, fullKey=full_key,
    )


@router.patch("/publications/{publication_id}", response_model=WriteResponse, summary="启停发布版本")
async def update_publication(
    publication_id: str,
    payload: PublicationPatch,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.ModelPublication, publication_id)
    patch = payload.model_dump(exclude_unset=True)
    for column, value in to_row(patch, m.ModelPublication).items():
        setattr(row, column, value)
    await db.flush()
    entity = publication_to_dict(row)
    return await commit_write(
        db, entity, "启用模型版本" if patch.get("enabled") else "停用模型版本",
        f"发布版本 {publication_id} 配置已更新", resource_id=row.model_id,
    )


@router.post("/publications/{publication_id}/key/rotate", response_model=WriteResponse, summary="轮换发布版本 Key")
async def rotate_publication_key(
    publication_id: str,
    payload: dict[str, str],
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.ModelPublication, publication_id)
    full_key = str(payload.get("apiKey", "")).strip()
    if len(full_key) < 12:
        raise ApiException("validation_error", "新 Key 至少需要 12 个字符，不允许空值或明显弱密钥", 400)
    row.credential_suffix = suffix_of(full_key)
    await store_credential(db, "model_publication_key", publication_id, full_key, row.credential_suffix)
    await db.flush()
    entity = publication_to_dict(row)
    return await commit_write(
        db, entity, "轮换模型发布 Key",
        f"{row.version} 的旧 Key 立即失效，新 Key 后缀 {row.credential_suffix}",
        resource_id=row.model_id, fullKey=full_key,
    )
