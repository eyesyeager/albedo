from datetime import datetime, timedelta, timezone
from urllib.parse import unquote, urlparse

from arq import cron
from arq.connections import RedisSettings
from sqlalchemy import delete, update

from app.core.config import get_settings
from app.db import models as m
from app.db.session import SessionLocal, dispose_engine
from app.services.audit import audit

_settings = get_settings()
_parsed = urlparse(_settings.redis_url)


def _iso(moment: datetime) -> str:
    return moment.isoformat(timespec="milliseconds").replace("+00:00", "Z")


async def cleanup_logs(ctx) -> dict:
    async with SessionLocal() as db:
        workspace = await db.get(m.WorkspaceSetting, 1)
        retention_days = workspace.retention_days if workspace else 90
        cutoff = _iso(datetime.now(timezone.utc) - timedelta(days=retention_days))
        result = await db.execute(delete(m.LogEntry).where(m.LogEntry.timestamp < cutoff))
        removed = int(result.rowcount or 0)
        await db.execute(update(m.ExportTask).where(m.ExportTask.expires_at < _iso(datetime.now(timezone.utc))).values(status="expired"))
        await audit(
            db,
            "执行日志清理任务",
            f"按 {retention_days} 天周期清理 {removed} 条过期日志，任务完成且可审计",
        )
        await db.commit()
    return {"deleted": removed}


async def shutdown(ctx) -> None:
    await dispose_engine()


class WorkerSettings:
    functions = [cleanup_logs]
    cron_jobs = [cron(cleanup_logs, hour=3, minute=0)]
    on_shutdown = shutdown
    redis_settings = RedisSettings(
        host=_parsed.hostname or "localhost",
        port=_parsed.port or 6379,
        password=unquote(_parsed.password) if _parsed.password else None,
        database=int((_parsed.path or "/0").lstrip("/") or 0),
    )
