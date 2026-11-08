from fastapi import APIRouter, Depends, Response
from sqlalchemy import delete, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.adapters.gateway import ModelGateway
from app.api.deps import current_session
from app.core.errors import ApiException
from app.core.security import decrypt_secret, new_id, now_iso, suffix_of
from app.db import models as m
from app.db.seed import to_row
from app.db.session import get_db
from app.schemas.domain import ModelResource, TestConnectionResult
from app.schemas.requests import EnabledPatch, ModelDraft, ModelPatch, ModelTestRequest
from app.schemas.responses import CredentialView, WriteResponse
from app.services.audit import audit
from app.services.system_config import get_configs
from app.services.common import (
    commit_write,
    get_or_404,
    model_to_dict,
    read_headers,
    reveal_credential,
    store_credential,
    validate_blank,
    validate_json_object,
    validate_name,
    write_headers,
)

router = APIRouter(tags=["models"])


@router.get("/models", response_model=list[ModelResource], summary="模型列表")
async def list_models(
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> list[dict]:
    rows = (await db.scalars(select(m.ModelResource))).all()
    return [model_to_dict(row) for row in rows]


@router.post("/models/test", response_model=TestConnectionResult, summary="模型连接测试（真实上游请求）")
async def test_model(
    payload: ModelTestRequest,
    _: m.Session = Depends(current_session),
) -> dict:
    return await ModelGateway().test_connection(payload.model_dump())


@router.post("/models/{model_id}/test", response_model=WriteResponse, summary="测试已接入模型的连接")
async def test_existing_model(
    model_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.ModelResource, model_id)
    credential = await db.scalar(
        select(m.Credential).where(
            m.Credential.type == "upstream_model_key",
            m.Credential.resource_id == model_id,
        )
    )
    api_key = decrypt_secret(credential.cipher) if credential and credential.cipher else ""
    stamp = now_iso()
    try:
        result = await ModelGateway().test_connection({
            "upstreamModelId": row.upstream_model_id,
            "type": row.type,
            "baseUrl": row.base_url,
            "apiKey": api_key or "",
            "headers": read_headers(row.headers_enc),
            "timeoutSeconds": row.timeout_seconds,
        })
    except ApiException as exc:
        row.connection = "error"
        row.latency_ms = 0
        row.last_tested_at = stamp
        row.updated_at = stamp
        await audit(db, "测试模型连接", f"「{row.name}」连接测试失败：{exc.message}", resource_id=model_id, status="failed")
        await db.commit()
        raise
    row.connection = "normal"
    row.latency_ms = int(result.get("latencyMs", 0))
    row.last_tested_at = stamp
    row.updated_at = stamp
    entity = model_to_dict(row)
    return await commit_write(
        db, entity, "测试模型连接",
        f"「{row.name}」连接测试通过，延迟 {row.latency_ms} ms",
        resource_id=model_id, latencyMs=row.latency_ms,
    )


@router.post("/models", response_model=WriteResponse, status_code=201, summary="接入模型")
async def create_model(
    payload: ModelDraft,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    name = validate_name(payload.name, "模型展示名称")
    upstream = validate_blank(payload.upstreamModelId, "Model ID 不能为空")
    base_url = validate_blank(payload.baseUrl, "Base URL 不能为空")
    api_key = validate_blank(payload.apiKey, "上游 API Key 不能为空")
    validate_json_object(payload.headers)
    config = await get_configs(db)
    probe = await ModelGateway().test_connection({
        "upstreamModelId": upstream,
        "type": payload.type,
        "baseUrl": base_url,
        "apiKey": api_key,
        "headers": payload.headers,
        "timeoutSeconds": payload.timeoutSeconds,
    })
    stamp = now_iso()
    row = m.ModelResource(
        id=new_id("mdl-"),
        name=name,
        upstream_model_id=upstream,
        type=payload.type,
        vendor=payload.vendor.strip() or config["defaults"]["model"]["vendor"] or "自建",
        base_url=base_url,
        credential_suffix=suffix_of(api_key),
        headers_enc=write_headers(payload.headers),
        timeout_seconds=payload.timeoutSeconds,
        note=payload.note,
        enabled=payload.enabled,
        connection="normal",
        latency_ms=int(probe["latencyMs"]),
        last_tested_at=stamp,
        created_at=stamp,
        updated_at=stamp,
    )
    db.add(row)
    await store_credential(db, "upstream_model_key", row.id, api_key, row.credential_suffix)
    entity = model_to_dict(row)
    return await commit_write(db, entity, "接入模型", f"接入「{name}」（{upstream}）", resource_id=row.id)


@router.get("/models/{model_id}", response_model=ModelResource, summary="模型详情")
async def get_model(
    model_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    return model_to_dict(await get_or_404(db, m.ModelResource, model_id))


@router.get("/models/{model_id}/credential", response_model=CredentialView, summary="查看模型上游 API Key")
async def reveal_model_credential(
    model_id: str,
    response: Response,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    await get_or_404(db, m.ModelResource, model_id)
    response.headers["Cache-Control"] = "no-store"
    return await reveal_credential(db, "upstream_model_key", model_id, "模型上游 API Key")


@router.patch("/models/{model_id}", response_model=WriteResponse, summary="保存模型配置")
async def update_model(
    model_id: str,
    payload: ModelPatch,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.ModelResource, model_id)
    patch = payload.model_dump(exclude_unset=True)
    if patch.get("baseUrl") is not None:
        validate_blank(patch["baseUrl"], "Base URL 不能为空")
    if "headers" in patch:
        validate_json_object(patch["headers"])
    updates = to_row(patch, m.ModelResource, aliases={"headers": "headers_enc"})
    if "headers" in patch:
        updates["headers_enc"] = write_headers(patch["headers"])
    for column, value in updates.items():
        setattr(row, column, value)
    if any(key in patch for key in ("baseUrl", "headers", "credentialSuffix")):
        row.connection = "untested"
    row.updated_at = now_iso()
    await db.flush()
    entity = model_to_dict(row)
    return await commit_write(db, entity, "保存模型配置", f"更新模型 {model_id} 的连接配置，连接状态已置为待复测", resource_id=model_id)


@router.patch("/models/{model_id}/enabled", response_model=WriteResponse, summary="启停模型")
async def toggle_model(
    model_id: str,
    payload: EnabledPatch,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.ModelResource, model_id)
    row.enabled = payload.enabled
    row.updated_at = now_iso()
    await db.flush()
    entity = model_to_dict(row)
    return await commit_write(
        db, entity, "启用模型" if payload.enabled else "停用模型",
        f"模型 {model_id} 已{'启用' if payload.enabled else '停用'}", resource_id=model_id,
    )


@router.delete("/models/{model_id}", response_model=WriteResponse, summary="删除模型")
async def remove_model(
    model_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.ModelResource, model_id)
    name = row.name
    publications = (
        await db.scalars(select(m.ModelPublication).where(m.ModelPublication.model_id == model_id))
    ).all()
    for publication in publications:
        await db.execute(
            delete(m.Credential).where(
                m.Credential.type == "model_publication_key",
                m.Credential.resource_id == publication.id,
            )
        )
        await db.delete(publication)
    await db.execute(
        delete(m.Credential).where(
            m.Credential.type == "upstream_model_key",
            m.Credential.resource_id == model_id,
        )
    )
    await db.execute(
        delete(m.AgentMount).where(m.AgentMount.kind == "model", m.AgentMount.resource_id == model_id)
    )
    await db.execute(
        update(m.Agent).where(m.Agent.default_model_id == model_id).values(default_model_id=None)
    )
    await db.execute(
        update(m.Agent).where(m.Agent.test_model_id == model_id).values(test_model_id=None)
    )
    await db.delete(row)
    return await commit_write(
        db, None, "删除模型",
        f"删除「{name}」及其 {len(publications)} 个发布版本，并解除 Agent 挂载",
        resource_id=model_id,
    )


def require_connection_normal(row: m.ModelResource) -> None:
    if not row.enabled or row.connection != "normal":
        raise ApiException("connection_test_required", "模型未启用或未通过连接测试，无法发布版本", 409)
