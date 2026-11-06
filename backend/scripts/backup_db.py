import asyncio
import json
import sys
from datetime import datetime
from pathlib import Path

from sqlalchemy import text

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.config import get_settings  # noqa: E402
from app.db.session import SessionLocal, dispose_engine  # noqa: E402


async def main() -> None:
    settings = get_settings()
    backup_dir = Path(settings.export_dir).parent / "backups"
    backup_dir.mkdir(parents=True, exist_ok=True)
    target = backup_dir / f"albedo-{datetime.now():%Y%m%d-%H%M%S}.json"

    async with SessionLocal() as session:
        tables = (await session.execute(text("SHOW TABLES"))).scalars().all()
        payload = {"createdAt": datetime.utcnow().isoformat() + "Z", "tables": {}, "schema": {}}
        for table in tables:
            rows = (await session.execute(text(f"SELECT * FROM `{table}`"))).mappings().all()
            payload["tables"][table] = [dict(row) for row in rows]
            create = (await session.execute(text(f"SHOW CREATE TABLE `{table}`"))).mappings().first()
            payload["schema"][table] = list(create.values())[1] if create else ""

    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    await dispose_engine()
    print(f"backup written: {target} ({target.stat().st_size} bytes, {len(payload['tables'])} tables)")


if __name__ == "__main__":
    asyncio.run(main())
