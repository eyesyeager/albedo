"""轮换 SECRET_KEY 后，把库内密文重加密到当前密钥环。

用法（先备份数据库，再在环境中提供新旧密钥）：
  SECRET_KEY=<new> SECRET_KEY_PREVIOUS=<old> PYTHONPATH=. \\
    .venv/bin/python scripts/rotate_secrets.py --confirm

环境变量优先级高于 .env，因此无需改动工作区里的 .env 即可演练。
"""

import argparse
import asyncio
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

from sqlalchemy import select  # noqa: E402

from app.core.security import rotate_secret  # noqa: E402
from app.db import models as m  # noqa: E402
from app.db.session import SessionLocal, dispose_engine  # noqa: E402

ENCRYPTED_COLUMNS = (
    (m.Credential, ("cipher",)),
    (m.ModelResource, ("headers_enc",)),
    (m.ModelPublication, ("headers_enc",)),
    (m.McpServer, ("headers_enc", "env_enc")),
)


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--confirm", action="store_true", help="确认已备份数据库")
    args = parser.parse_args()
    if not args.confirm:
        print("请先备份数据库，并显式加 --confirm 执行", file=sys.stderr)
        return

    rotated = skipped = 0
    async with SessionLocal() as db:
        for model, columns in ENCRYPTED_COLUMNS:
            for row in (await db.scalars(select(model))).all():
                for column in columns:
                    value = getattr(row, column) or ""
                    if not value:
                        continue
                    new_value = rotate_secret(value)
                    if new_value is None:
                        skipped += 1
                        continue
                    setattr(row, column, new_value)
                    rotated += 1
        await db.commit()
    await dispose_engine()
    print(f"rotated={rotated} skipped={skipped}")


if __name__ == "__main__":
    asyncio.run(main())
