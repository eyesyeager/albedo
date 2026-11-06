import asyncio
import json
import sys
from pathlib import Path
from typing import Any

from sqlalchemy import text

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.db.session import SessionLocal, dispose_engine  # noqa: E402


def normalize(value: Any) -> Any:
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, str) and len(value) >= 19 and value[4] == "-" and value[10] == "T":
        return value.replace("T", " ").replace("Z", "")[:26]
    return value


async def main() -> None:
    if len(sys.argv) < 2 or "--confirm" not in sys.argv:
        raise SystemExit("用法: python scripts/restore_db.py <backup.json> --confirm（会清空并恢复目标库）")
    payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    async with SessionLocal() as session:
        for table in payload["tables"]:
            await session.execute(text(f"DELETE FROM `{table}`"))
        for table, rows in payload["tables"].items():
            for row in rows:
                columns = ", ".join(f"`{column}`" for column in row)
                values = ", ".join(f":{column}" for column in row)
                await session.execute(
                    text(f"INSERT INTO `{table}` ({columns}) VALUES ({values})"),
                    {key: normalize(value) for key, value in row.items()},
                )
        await session.commit()
    await dispose_engine()
    print(f"restored {len(payload['tables'])} tables from {sys.argv[1]}")


if __name__ == "__main__":
    asyncio.run(main())
