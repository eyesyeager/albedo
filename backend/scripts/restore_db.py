"""从 backup_db.py 生成的备份恢复数据库。

表名与列名全部按 ORM 元数据白名单校验，杜绝备份文件里的标识符被拼进 SQL。
"""

import asyncio
import json
import sys
from pathlib import Path
from typing import Any

from sqlalchemy import text

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.security import decrypt_secret  # noqa: E402
from app.db.base import Base  # noqa: E402
from app.db.session import SessionLocal, dispose_engine  # noqa: E402
import app.db.models  # noqa: E402,F401 - 注册全部 ORM 表


def normalize(value: Any) -> Any:
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, str) and len(value) >= 19 and value[4] == "-" and value[10] == "T":
        return value.replace("T", " ").replace("Z", "")[:26]
    return value


def load_payload(path: Path) -> dict:
    raw = path.read_text(encoding="utf-8").strip()
    if path.suffix == ".enc" or not raw.startswith("{"):
        plaintext = decrypt_secret(raw)
        if plaintext is None:
            raise SystemExit("备份解密失败：请检查 SECRET_KEY / SECRET_KEY_PREVIOUS 是否与备份时一致")
        return json.loads(plaintext)
    print("警告：正在从历史明文备份恢复，建议恢复后用 backup_db.py 重新生成加密备份", file=sys.stderr)
    return json.loads(raw)


async def main() -> None:
    if len(sys.argv) < 2 or "--confirm" not in sys.argv:
        raise SystemExit("用法: python scripts/restore_db.py <backup.json[.enc]> --confirm（会清空并恢复目标库）")
    payload = load_payload(Path(sys.argv[1]))

    allowed: dict[str, set[str]] = {
        name: set(table.columns.keys()) for name, table in Base.metadata.tables.items()
    }
    # alembic_version 不是 ORM 模型，但恢复后必须保持迁移版本一致。
    allowed["alembic_version"] = {"version_num"}
    unknown = set(payload.get("tables") or {}) - set(allowed)
    if unknown:
        print(f"跳过未识别的表：{', '.join(sorted(unknown))}", file=sys.stderr)

    async with SessionLocal() as session:
        for table in payload["tables"]:
            if table in allowed:
                await session.execute(text(f"DELETE FROM `{table}`"))
        for table, rows in payload["tables"].items():
            if table not in allowed:
                continue
            columns = allowed[table]
            for row in rows:
                safe = {key: value for key, value in row.items() if key in columns}
                if not safe:
                    continue
                names = ", ".join(f"`{key}`" for key in safe)
                placeholders = ", ".join(f":{key}" for key in safe)
                await session.execute(
                    text(f"INSERT INTO `{table}` ({names}) VALUES ({placeholders})"),
                    {key: normalize(value) for key, value in safe.items()},
                )
        await session.commit()
    await dispose_engine()
    print(f"restored {len(payload['tables'])} tables from {sys.argv[1]}")


if __name__ == "__main__":
    asyncio.run(main())
