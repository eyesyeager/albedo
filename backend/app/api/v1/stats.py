from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Query
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_session
from app.db import models as m
from app.db.session import get_db
from app.schemas.domain import OverviewStats, SearchResult, TrendPoint, normalize_log_type
from app.services.system_config import get_configs
from app.services.trends import build_trend_points, local_date

router = APIRouter(tags=["stats"])

LOCAL_TZ = timezone(timedelta(hours=8))


@router.get("/stats/overview", response_model=OverviewStats, summary="概览指标")
async def overview(
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    logs = (await db.scalars(select(m.LogEntry))).all()
    agents = (await db.scalars(select(m.Agent))).all()
    models = (await db.scalars(select(m.ModelResource))).all()
    mcps = (await db.scalars(select(m.McpServer))).all()

    day_ago = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    recent = [log for log in logs if log.timestamp >= day_ago]
    ended = [log for log in recent if log.status != "running"]
    succeeded = sum(1 for log in ended if log.status == "success")
    durations = [log.duration_ms for log in recent if log.duration_ms is not None]
    today = datetime.now(LOCAL_TZ).strftime("%Y-%m-%d")
    yesterday = (datetime.now(LOCAL_TZ) - timedelta(days=1)).strftime("%Y-%m-%d")
    today_calls = sum(1 for log in logs if local_date(log.timestamp) == today)
    yesterday_calls = sum(1 for log in logs if local_date(log.timestamp) == yesterday)

    grouped = (await db.execute(select(m.LogEntry.type, func.count()).group_by(m.LogEntry.type))).all()
    type_counts: dict[str, int] = {}
    for raw_type, count in grouped:
        canonical = normalize_log_type(raw_type)
        type_counts[canonical] = type_counts.get(canonical, 0) + int(count)

    abnormal = sum(1 for model in models if model.enabled and model.connection != "normal")
    abnormal += sum(1 for mcp in mcps if mcp.enabled and mcp.connection != "normal")

    today_durations = [log.duration_ms for log in logs if local_date(log.timestamp) == today and log.duration_ms is not None]
    yesterday_durations = [log.duration_ms for log in logs if local_date(log.timestamp) == yesterday and log.duration_ms is not None]
    today_avg = sum(today_durations) / len(today_durations) if today_durations else 0.0
    yesterday_avg = sum(yesterday_durations) / len(yesterday_durations) if yesterday_durations else 0.0
    avg_duration_change = (today_avg - yesterday_avg) / yesterday_avg * 100 if yesterday_avg else 0.0

    return {
        "agentsTotal": len(agents),
        "agentsPublished": sum(1 for agent in agents if agent.api_enabled or agent.agui_enabled),
        "todayCalls": today_calls,
        "callsChangePercent": (today_calls - yesterday_calls) / yesterday_calls * 100 if yesterday_calls else 0.0,
        "modelsTotal": len(models),
        "modelsEnabled": sum(1 for model in models if model.enabled),
        "successRate": succeeded / len(ended) * 100 if ended else 0.0,
        "avgDurationMs": round(sum(durations) / len(durations)) if durations else 0,
        "avgDurationChange": avg_duration_change,
        "abnormalCount": abnormal,
        "typeCounts": type_counts,
    }


@router.get("/stats/trends", response_model=list[TrendPoint], summary="调用趋势")
async def trends(
    days: int = Query(default=14, ge=1, le=365),
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> list[dict]:
    timestamps = (await db.scalars(select(m.LogEntry.timestamp))).all()
    return build_trend_points(list(timestamps), days=days)


@router.get("/search", response_model=list[SearchResult], summary="全局搜索")
async def global_search(
    q: str = "",
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> list[dict]:
    config = await get_configs(db)
    limit = int(config["limits"]["searchMaxResults"])
    keyword = q.strip()
    if not keyword:
        return []
    like = f"%{keyword}%"
    results: list[dict] = []
    for agent in (await db.scalars(select(m.Agent).where(m.Agent.name.like(like)).limit(limit))).all():
        results.append({
            "id": agent.id, "name": agent.name, "kind": "agent",
            "subtitle": agent.description, "path": f"/agents/{agent.id}",
        })
    for model in (await db.scalars(select(m.ModelResource).where(m.ModelResource.name.like(like)).limit(limit))).all():
        results.append({
            "id": model.id, "name": model.name, "kind": "model",
            "subtitle": f"{model.vendor} · {model.upstream_model_id}", "path": "/resources/models",
        })
    return results[:limit]
