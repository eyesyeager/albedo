import hashlib
import hmac
import json
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import FileResponse
from sqlalchemy import func, or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_session
from app.core.config import get_settings
from app.core.errors import ApiException
from app.core.logging import mask_text, sanitize
from app.core.security import iso_after, new_id, now_iso
from app.db import models as m
from app.db.session import get_db
from app.schemas.domain import ExportTask, LogDetail, PageResult
from app.schemas.requests import ExportRequest
from app.schemas.responses import WriteResponse
from app.services.audit import audit
from app.services.common import get_or_404
from app.services.snapshot import row_dict
from app.services.system_config import get_configs

router = APIRouter(tags=["logs"])

CSV_HEADERS = ["时间", "类型", "状态", "来源", "调用对象", "摘要", "智能体", "Run ID", "耗时(ms)", "错误码"]


def task_to_dict(row: m.ExportTask) -> dict[str, Any]:
    return {
        "id": row.id,
        "createdAt": row.created_at,
        "format": row.format,
        "filters": row.filters_label,
        "recordCount": row.record_count,
        "fileSizeBytes": row.file_size_bytes,
        "expiresAt": row.expires_at,
        "status": row.status,
    }


def csv_cell(value: Any) -> str:
    return '"' + str(value if value is not None else "").replace('"', '""') + '"'


def build_csv(logs: list[dict[str, Any]]) -> str:
    rows = [CSV_HEADERS]
    for log in logs:
        rows.append([
            log.get("timestamp"), log.get("type"), log.get("status"), log.get("source"),
            mask_text(str(log.get("name") or "")), mask_text(str(log.get("summary") or "")),
            log.get("agentId"), log.get("runId"), log.get("durationMs"), log.get("errorCode"),
        ])
    return "\n".join(",".join(csv_cell(cell) for cell in row) for row in rows)


def build_json(logs: list[dict[str, Any]]) -> str:
    return json.dumps([sanitize(log) for log in logs], ensure_ascii=False, indent=2)


def sign_download_url(task_id: str, ttl_seconds: int) -> str:
    expires = int(time.time()) + ttl_seconds
    secret = get_settings().secret_key.encode()
    signature = hmac.new(secret, f"{task_id}:{expires}".encode(), hashlib.sha256).hexdigest()
    return f"/api/v1/logs/exports/{task_id}/download?expires={expires}&signature={signature}"


def verify_download_signature(task_id: str, expires: str | None, signature: str | None) -> bool:
    if not expires or not signature:
        return False
    try:
        deadline = int(expires)
    except ValueError:
        return False
    if deadline < int(time.time()):
        return False
    secret = get_settings().secret_key.encode()
    expected = hmac.new(secret, f"{task_id}:{deadline}".encode(), hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature)


async def log_conditions(
    db: AsyncSession,
    *,
    keyword: str,
    status: str,
    range: str,
    agentId: str,
    runId: str = "",
) -> list[Any]:
    conditions: list[Any] = [m.LogEntry.timestamp >= range_cutoff(range)]
    if status != "all":
        conditions.append(m.LogEntry.status == status)
    if agentId != "all":
        conditions.append(m.LogEntry.agent_id == agentId)
    # runId 精确匹配用于「对话历史」「执行链路」深链，不依赖前端快照。
    if runId.strip():
        conditions.append(m.LogEntry.run_id == runId.strip())
    if keyword.strip():
        like = f"%{keyword.strip()}%"
        agent_ids = [
            row.id for row in (await db.scalars(select(m.Agent).where(m.Agent.name.like(like)))).all()
        ]
        conditions.append(or_(
            m.LogEntry.name.like(like),
            m.LogEntry.summary.like(like),
            m.LogEntry.run_id.like(like),
            m.LogEntry.thread_id.like(like),
            m.LogEntry.agent_id.like(like),
            m.LogEntry.agent_id.in_(agent_ids),
        ))
    return conditions


def range_cutoff(value: str) -> str:
    now = datetime.now(timezone.utc)
    if value == "today":
        local_start = datetime.now(timezone(timedelta(hours=8))).replace(hour=0, minute=0, second=0, microsecond=0)
        start = local_start.astimezone(timezone.utc)
    elif value == "30d":
        start = now - timedelta(days=30)
    elif value == "1y":
        start = now - timedelta(days=365)
    else:
        start = now - timedelta(days=7)
    return start.isoformat(timespec="milliseconds").replace("+00:00", "Z")


@router.get("/logs", response_model=PageResult, summary="日志分页筛选")
async def query_logs(
    page: int = Query(default=1, ge=1),
    pageSize: int = Query(default=20, ge=1, le=200),
    keyword: str = "",
    type: str = "all",
    status: str = "all",
    range: str = "7d",
    agentId: str = "all",
    runId: str = "",
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    conditions = await log_conditions(
        db, keyword=keyword, status=status, range=range, agentId=agentId, runId=runId
    )

    grouped = (
        await db.execute(select(m.LogEntry.type, func.count()).where(*conditions).group_by(m.LogEntry.type))
    ).all()
    type_counts = {row[0]: int(row[1]) for row in grouped}

    query_conditions = list(conditions)
    if type != "all":
        query_conditions.append(m.LogEntry.type == type)
    total = await db.scalar(select(func.count()).select_from(m.LogEntry).where(*query_conditions)) or 0
    rows = (
        await db.scalars(
            select(m.LogEntry)
            .where(*query_conditions)
            .order_by(m.LogEntry.timestamp.desc())
            .offset((page - 1) * pageSize)
            .limit(pageSize)
        )
    ).all()
    return {
        "items": [row_dict(row) for row in rows],
        "total": int(total),
        "page": page,
        "pageSize": pageSize,
        "typeCounts": type_counts,
    }


async def generate_export(db: AsyncSession, row: m.ExportTask) -> dict[str, Any] | None:
    """真实生成导出文件并落库统计，调用方负责提交事务。"""
    filters = row.filters_json or {}
    log_ids = filters.get("logIds") or []
    if log_ids:
        records = (await db.scalars(select(m.LogEntry).where(m.LogEntry.log_id.in_(log_ids)).order_by(m.LogEntry.timestamp.desc()))).all()
    else:
        conditions = await log_conditions(
            db,
            keyword=str(filters.get("keyword") or ""),
            status=str(filters.get("status") or "all"),
            range=str(filters.get("range") or "7d"),
            agentId=str(filters.get("agentId") or "all"),
        )
        if filters.get("type") not in (None, "all"):
            conditions.append(m.LogEntry.type == filters["type"])
        records = (await db.scalars(select(m.LogEntry).where(*conditions).order_by(m.LogEntry.timestamp.desc()))).all()
    logs = [row_dict(record) for record in records]
    content = build_csv(logs) if row.format == "csv" else build_json(logs)
    directory = Path(get_settings().export_dir)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{row.id}.{row.format}"
    path.write_text("\ufeff" + content, encoding="utf-8")
    row.file_path = str(path)
    row.file_size_bytes = path.stat().st_size
    row.record_count = len(logs)
    row.status = "completed"
    return await audit(
        db, "导出调用日志",
        f"任务 {row.id}：条件「{row.filters_label}」，{len(logs)} 条记录，{row.format.upper()}，已脱敏",
    )


@router.post("/logs/exports", response_model=ExportTask, status_code=202, summary="创建并生成日志导出任务")
async def create_export_task(
    payload: ExportRequest,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    config = await get_configs(db)
    limits = config["limits"]
    row = m.ExportTask(
        id=new_id("exp-"),
        created_at=now_iso(),
        format=payload.format,
        filters_json={**payload.filters, "logIds": payload.logIds},
        filters_label=payload.filtersLabel or "全部日志",
        record_count=0,
        file_size_bytes=0,
        expires_at=iso_after(days=int(limits["exportExpireDays"])),
        status="pending",
        file_path="",
    )
    db.add(row)
    await db.flush()
    await generate_export(db, row)
    await db.commit()
    return task_to_dict(row)


@router.get("/logs/exports/{task_id}", response_model=WriteResponse, summary="查询导出任务")
async def get_export_task(
    task_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.ExportTask, task_id)
    log = None
    if row.status == "pending":
        log = await generate_export(db, row)
        await db.commit()
    config = await get_configs(db)
    ttl = int(config["limits"]["downloadSignTtlSeconds"])
    download_url = sign_download_url(task_id, ttl) if row.status == "completed" else None
    return {"task": task_to_dict(row), "log": log, "downloadUrl": download_url}


@router.get("/logs/exports/{task_id}/download", summary="下载导出文件")
async def download_export(
    task_id: str,
    request: Request,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> FileResponse:
    expires = request.query_params.get("expires")
    signature = request.query_params.get("signature")
    # 下载链接必须携带由轮询接口签发的一次性签名，未签名请求一律拒绝。
    if not verify_download_signature(task_id, expires, signature):
        raise ApiException("invalid_api_key", "下载链接已过期或签名无效，请重新导出", 403)
    row = await get_or_404(db, m.ExportTask, task_id)
    if row.status != "completed" or not row.file_path or not Path(row.file_path).exists():
        raise ApiException("resource_not_found", "导出文件尚未生成或已过期", 404)
    media_type = "text/csv; charset=utf-8" if row.format == "csv" else "application/json; charset=utf-8"
    return FileResponse(row.file_path, filename=f"albedo-logs-{task_id}.{row.format}", media_type=media_type)


@router.get("/logs/{log_id}", response_model=LogDetail, summary="日志详情与执行链路")
async def log_detail(
    log_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.LogEntry, log_id)
    detail = row_dict(row)
    chain: list[dict[str, Any]] = []
    if row.run_id:
        related = (
            await db.scalars(select(m.LogEntry).where(m.LogEntry.run_id == row.run_id).order_by(m.LogEntry.timestamp.asc()))
        ).all()
        chain = [row_dict(item) for item in related]
    detail["chain"] = chain
    return detail


async def prune_logs(db: AsyncSession, retention_days: int) -> int:
    cutoff = (datetime.now(timezone.utc) - timedelta(days=retention_days)).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    from sqlalchemy import delete

    result = await db.execute(delete(m.LogEntry).where(m.LogEntry.timestamp < cutoff))
    return int(result.rowcount or 0)


async def mark_expired_exports(db: AsyncSession) -> None:
    await db.execute(update(m.ExportTask).where(m.ExportTask.expires_at < now_iso()).values(status="expired"))
