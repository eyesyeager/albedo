import json
import logging
import time

import httpx
from fastapi import APIRouter, Depends, Request
from fastapi.responses import Response, StreamingResponse
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.public.deps import require_publication
from app.core.errors import ApiException
from app.core.ratelimit import allow_qpm
from app.core.security import decrypt_secret, suffix_of
from app.db import models as m
from app.db.seed import to_row
from app.db.session import SessionLocal, get_db
from app.services.audit import make_log_entry
from app.services.common import read_headers
from app.services.system_config import limit as config_limit

router = APIRouter()


async def load_upstream_credential(db: AsyncSession, model: m.ModelResource) -> m.Credential:
    credential = await db.scalar(
        select(m.Credential).where(
            m.Credential.type == "upstream_model_key",
            m.Credential.resource_id == model.id,
        )
    )
    if credential is None:
        raise ApiException("model_version_disabled", "模型未配置上游凭证，无法转发", 409)
    return credential


def upstream_headers(model: m.ModelResource, credential: m.Credential) -> dict[str, str]:
    upstream_key = decrypt_secret(credential.cipher) or ""
    headers: dict[str, str] = {"Content-Type": "application/json"}
    if upstream_key:
        headers["Authorization"] = f"Bearer {upstream_key}"
    try:
        custom = json.loads(read_headers(model.headers_enc) or "{}")
        if isinstance(custom, dict):
            headers.update({str(key): str(value) for key, value in custom.items()})
    except json.JSONDecodeError:
        pass
    for banned in ("host", "content-length", "connection", "transfer-encoding"):
        headers.pop(banned, None)
    return headers


def merge_body(body: dict, publication: m.ModelPublication, model: m.ModelResource) -> dict:
    merged = dict(body)
    merged["model"] = model.upstream_model_id
    try:
        temperature = float(body.get("temperature", publication.temperature))
    except (TypeError, ValueError):
        temperature = publication.temperature
    merged["temperature"] = min(float(config_limit("temperatureMax", 2)), max(float(config_limit("temperatureMin", 0)), temperature))
    requested = body.get("max_tokens") or body.get("max_completion_tokens")
    if requested:
        try:
            merged["max_tokens"] = min(publication.max_output_tokens, max(1, int(requested)))
        except (TypeError, ValueError):
            merged["max_tokens"] = publication.max_output_tokens
    else:
        merged["max_tokens"] = publication.max_output_tokens
    merged.pop("max_completion_tokens", None)
    return merged


async def require_enabled(publication: m.ModelPublication, model: m.ModelResource) -> None:
    if not publication.enabled or not model.enabled:
        raise ApiException("model_version_disabled", "发布版本或模型已停用", 409)
    if not await allow_qpm(f"pub:{publication.id}", publication.qpm):
        raise ApiException("rate_limit_exceeded", "超过模型版本 QPM 配额，请稍后重试", 429)


async def write_model_log(
    db: AsyncSession,
    model: m.ModelResource,
    publication: m.ModelPublication,
    token_suffix: str,
    status: str,
    duration_ms: int,
    error_code: str | None = None,
    error_message: str | None = None,
) -> str:
    entry = make_log_entry(
        f"{model.name} / 对外 API",
        f"{publication.call_identifier} → {'成功' if status == 'success' else status}",
        log_type="model",
        source="model_api",
        status=status,
        resource_id=model.id,
        durationMs=duration_ms,
        credentialSuffix=token_suffix,
        errorCode=error_code,
        errorMessage=error_message,
    )
    db.add(m.LogEntry(**to_row(entry, m.LogEntry)))
    await db.commit()
    return str(entry["logId"])


async def finish_model_log(
    log_id: str, *, status: str, duration_ms: int,
    error_code: str | None = None, error_message: str | None = None,
) -> None:
    """流式响应结束后使用独立事务回填终态，避免日志永远停在 running。"""
    try:
        async with SessionLocal() as db:
            await db.execute(
                update(m.LogEntry)
                .where(m.LogEntry.log_id == log_id)
                .values(status=status, duration_ms=duration_ms, error_code=error_code, error_message=error_message)
            )
            await db.commit()
    except Exception:  # noqa: BLE001
        logging.getLogger("albedo.public.models").exception("流式日志终态回填失败：%s", log_id)


async def proxy_stream(url: str, headers: dict[str, str], payload: dict, timeout: int, log_id: str):
    started = time.perf_counter()
    status = "success"
    error_code = None
    error_message = None
    client = httpx.AsyncClient(timeout=timeout)
    try:
        async with client.stream("POST", url, json=payload, headers=headers) as response:
            if response.status_code >= 400:
                await response.aread()
                status = "failed"
                error_code = "upstream_error" if response.status_code >= 500 else "invalid_request"
                error_message = f"上游返回 HTTP {response.status_code}"
                code = error_code
                raise ApiException(code, error_message, 502 if response.status_code >= 500 else 400)
            async for chunk in response.aiter_raw():
                yield chunk
    except Exception as exc:  # noqa: BLE001
        status = "failed"
        error_code = error_code or "upstream_error"
        error_message = error_message or str(exc)[:200]
        raise
    finally:
        await client.aclose()
        await finish_model_log(
            log_id, status=status, duration_ms=int((time.perf_counter() - started) * 1000),
            error_code=error_code, error_message=error_message,
        )


@router.get("/models")
async def list_models(request: Request, db: AsyncSession = Depends(get_db)) -> dict:
    publication, model, _ = await require_publication(request, db)
    if not publication.enabled or not model.enabled:
        raise ApiException("model_version_disabled", "发布版本或模型已停用", 409)
    return {
        "object": "list",
        "data": [{
            "id": publication.call_identifier,
            "object": "model",
            "created": int(time.time()),
            "owned_by": model.vendor,
            "root": model.upstream_model_id,
        }],
    }


@router.post("/chat/completions")
async def chat_completions(request: Request, db: AsyncSession = Depends(get_db)):
    body = await request.json()
    publication, model, credential = await require_publication(request, db)
    if str(body.get("model") or "") != publication.call_identifier:
        raise ApiException("model_not_allowed", "当前 Key 无权调用请求中的模型标识", 403)
    await require_enabled(publication, model)

    payload = merge_body(body, publication, model)
    url = f"{model.base_url.rstrip('/')}/chat/completions"
    upstream_credential = await load_upstream_credential(db, model)
    headers = upstream_headers(model, upstream_credential)
    timeout = max(5, model.timeout_seconds)
    token_suffix = suffix_of(credential.suffix or "")

    if body.get("stream"):
        log_id = await write_model_log(db, model, publication, token_suffix, "running", 0)
        return StreamingResponse(
            proxy_stream(url, headers, payload, timeout, log_id),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    started = time.perf_counter()
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post(url, json=payload, headers=headers)
    except httpx.TimeoutException as exc:
        await write_model_log(db, model, publication, token_suffix, "failed", int((time.perf_counter() - started) * 1000), "upstream_timeout", "上游响应超时")
        raise ApiException("upstream_timeout", "上游响应超时", 504) from exc
    except httpx.HTTPError as exc:
        await write_model_log(db, model, publication, token_suffix, "failed", int((time.perf_counter() - started) * 1000), "upstream_error", "上游服务不可达")
        raise ApiException("upstream_error", "上游服务不可达", 502) from exc
    duration_ms = int((time.perf_counter() - started) * 1000)
    if response.status_code >= 400:
        await write_model_log(db, model, publication, token_suffix, "failed", duration_ms, "upstream_error", f"上游 HTTP {response.status_code}")
        code = "upstream_error" if response.status_code >= 500 else "invalid_request"
        raise ApiException(code, f"上游返回 HTTP {response.status_code}", 502 if response.status_code >= 500 else 400)
    await write_model_log(db, model, publication, token_suffix, "success", duration_ms)
    return Response(content=response.content, media_type="application/json", status_code=response.status_code)


@router.post("/embeddings")
async def embeddings(request: Request, db: AsyncSession = Depends(get_db)):
    body = await request.json()
    publication, model, credential = await require_publication(request, db)
    if str(body.get("model") or "") != publication.call_identifier:
        raise ApiException("model_not_allowed", "当前 Key 无权调用请求中的模型标识", 403)
    if model.type != "embedding":
        raise ApiException("invalid_request", "该发布版本不是 Embedding 模型", 400)
    await require_enabled(publication, model)

    payload = dict(body)
    payload["model"] = model.upstream_model_id
    url = f"{model.base_url.rstrip('/')}/embeddings"
    upstream_credential = await load_upstream_credential(db, model)
    headers = upstream_headers(model, upstream_credential)
    timeout = max(5, model.timeout_seconds)
    token_suffix = suffix_of(credential.suffix or "")
    started = time.perf_counter()
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post(url, json=payload, headers=headers)
    except httpx.TimeoutException as exc:
        await write_model_log(db, model, publication, token_suffix, "failed", int((time.perf_counter() - started) * 1000), "upstream_timeout", "上游响应超时")
        raise ApiException("upstream_timeout", "上游响应超时", 504) from exc
    except httpx.HTTPError as exc:
        await write_model_log(db, model, publication, token_suffix, "failed", int((time.perf_counter() - started) * 1000), "upstream_error", "上游服务不可达")
        raise ApiException("upstream_error", "上游服务不可达", 502) from exc
    duration_ms = int((time.perf_counter() - started) * 1000)
    if response.status_code >= 400:
        await write_model_log(db, model, publication, token_suffix, "failed", duration_ms, "upstream_error", f"上游 HTTP {response.status_code}")
        code = "upstream_error" if response.status_code >= 500 else "invalid_request"
        raise ApiException(code, f"上游返回 HTTP {response.status_code}", 502 if response.status_code >= 500 else 400)
    await write_model_log(db, model, publication, token_suffix, "success", duration_ms)
    return Response(content=response.content, media_type="application/json", status_code=response.status_code)
