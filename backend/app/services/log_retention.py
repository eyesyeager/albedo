"""日志留存清理。

清理前先把待删除记录脱敏归档为本地 JSONL（0600），再分批删除：
既保证「一次 PATCH 清空日志」可恢复，也避免单条无界 DELETE 长时间锁表。
"""

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.logging import sanitize
from app.core.security import new_id
from app.db import models as m
from app.services.snapshot import log_dict

ARCHIVE_BATCH_SIZE = 500
MIN_RETENTION_DAYS = 1
MAX_RETENTION_DAYS = 365


def clamp_retention_days(value: int) -> int:
    return max(MIN_RETENTION_DAYS, min(MAX_RETENTION_DAYS, int(value)))


def _archive_dir() -> Path:
    return Path(get_settings().export_dir).parent / "archive"


async def prune_logs(db: AsyncSession, retention_days: int) -> dict[str, Any]:
    days = clamp_retention_days(retention_days)
    cutoff = (
        datetime.now(timezone.utc) - timedelta(days=days)
    ).isoformat(timespec="milliseconds").replace("+00:00", "Z")

    directory = _archive_dir()
    directory.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    archive_file = directory / f"logs-{stamp}-{new_id('', 6)}.jsonl"

    deleted = 0
    while True:
        rows = (
            await db.scalars(
                select(m.LogEntry).where(m.LogEntry.timestamp < cutoff).limit(ARCHIVE_BATCH_SIZE)
            )
        ).all()
        if not rows:
            break
        with archive_file.open("a", encoding="utf-8") as handle:
            for row in rows:
                handle.write(json.dumps(sanitize(log_dict(row)), ensure_ascii=False) + "\n")
        await db.execute(delete(m.LogEntry).where(m.LogEntry.log_id.in_([row.log_id for row in rows])))
        await db.flush()
        deleted += len(rows)

    if deleted:
        archive_file.chmod(0o600)
        return {"deleted": deleted, "archive": archive_file.name, "cutoff": cutoff}
    archive_file.unlink(missing_ok=True)
    return {"deleted": 0, "archive": "", "cutoff": cutoff}
