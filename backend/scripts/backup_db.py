"""数据库备份：排除会话凭证、剔除运行快照，并对备份文件加密 + 0600。

恢复请用 scripts/restore_db.py，支持 .enc 密文与历史明文备份。
"""

import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy import text

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.config import get_settings  # noqa: E402
from app.core.security import encrypt_secret  # noqa: E402
from app.db.session import SessionLocal, dispose_engine  # noqa: E402

# sessions.id 就是可重放的登录 Cookie，绝不进入备份。
EXCLUDED_TABLES = frozenset({"sessions"})
# 历史版本的运行快照可能残留解密后的请求头 / 环境变量，恢复时按空对象处理。
DROPPED_COLUMNS = {"runs": frozenset({"config_snapshot"})}


async def main() -> None:
    settings = get_settings()
    backup_dir = Path(settings.export_dir).parent / "backups"
    backup_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    target = backup_dir / f"albedo-{stamp}.json.enc"

    async with SessionLocal() as session:
        tables = (await session.execute(text("SHOW TABLES"))).scalars().all()
        payload: dict = {
            "createdAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
            "encrypted": True,
            "tables": {},
            "schema": {},
        }
        for table in tables:
            if table in EXCLUDED_TABLES:
                continue
            create = (await session.execute(text(f"SHOW CREATE TABLE `{table}`"))).mappings().first()
            payload["schema"][table] = list(create.values())[1] if create else ""
            rows = (await session.execute(text(f"SELECT * FROM `{table}`"))).mappings().all()
            dropped = DROPPED_COLUMNS.get(table, frozenset())
            payload["tables"][table] = [
                {key: ({} if key in dropped else value) for key, value in dict(row).items()}
                for row in rows
            ]

    raw = json.dumps(payload, ensure_ascii=False, indent=2, default=str)
    target.write_text(encrypt_secret(raw), encoding="utf-8")
    target.chmod(0o600)
    await dispose_engine()
    print(
        f"backup written: {target} ({target.stat().st_size} bytes, "
        f"{len(payload['tables'])} tables, encrypted, mode 0600)"
    )


if __name__ == "__main__":
    asyncio.run(main())
