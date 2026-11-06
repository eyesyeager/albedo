"""把数据库恢复到「最初未使用」状态：清空全量业务/运行数据，仅保留系统必需配置。

保留：
  - alembic_version：迁移版本元数据（不动）
  - workspace_settings：1 行，重置为初始默认配置
  - credentials：仅 type=management_access_key 的登录凭证（保持当前访问 Key 可用）
  - builtin_tools：平台内置能力定义（默认保留，call_count 归零、enabled 恢复默认；
    加 --purge-builtin-tools 可一并清空）

清空：agents / agent_mounts / models / model_publications / mcp_servers / mcp_tools /
mcp_call_records / skills / skill_versions / runs / chat_messages / log_entries /
export_tasks / notifications / trend_daily / sessions，以及其余业务凭据。

用法：
    PYTHONPATH=. .venv/bin/python scripts/reset_to_pristine.py                # 预演
    PYTHONPATH=. .venv/bin/python scripts/reset_to_pristine.py --confirm       # 执行
    PYTHONPATH=. .venv/bin/python scripts/reset_to_pristine.py --confirm --purge-builtin-tools
"""
import argparse
import asyncio
import sys
from pathlib import Path

from sqlalchemy import delete, select, text, update

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.security import now_iso  # noqa: E402
from app.db import models as m  # noqa: E402
from app.db.seed import BUSINESS_TABLES, to_row  # noqa: E402
from app.db.seed_data import make_seed  # noqa: E402
from app.db.session import SessionLocal, dispose_engine  # noqa: E402

PURGE_TABLES = tuple(model for model in BUSINESS_TABLES if model not in (m.WorkspaceSetting, m.BuiltinTool))
KEEP_NONEMPTY = {"workspace_settings", "system_configs", "credentials", "builtin_tools", "alembic_version"}


async def all_table_counts(session) -> dict[str, int]:
    tables = (await session.execute(text("SHOW TABLES"))).scalars().all()
    return {name: int((await session.execute(text(f"SELECT COUNT(*) FROM `{name}`"))).scalar() or 0) for name in tables}


async def reset(*, confirm: bool, purge_builtin_tools: bool) -> int:
    try:
        async with SessionLocal() as session:
            before = await all_table_counts(session)
            print("清空前行数:")
            for name, count in sorted(before.items()):
                print(f"  {name:<24} {count}")

            if not confirm:
                print("预演模式：未修改任何数据。确认后加 --confirm 执行。")
                return 0

            key_row = await session.scalar(select(m.Credential).where(m.Credential.type == "management_access_key"))
            if key_row is None:
                raise RuntimeError("缺少 management_access_key 凭证，终止清库以避免系统无法登录")

            for model in PURGE_TABLES:
                await session.execute(delete(model))
            await session.execute(delete(m.WorkspaceSetting))
            await session.execute(delete(m.Session))
            await session.execute(delete(m.Credential).where(m.Credential.type != "management_access_key"))
            if purge_builtin_tools:
                await session.execute(delete(m.BuiltinTool))
            else:
                await session.execute(update(m.BuiltinTool).values(call_count=0, enabled=True))

            defaults = to_row(make_seed()["settings"], m.WorkspaceSetting, drop={"accessKey"})
            defaults["id"] = 1
            defaults["access_key_suffix"] = key_row.suffix
            defaults["updated_at"] = now_iso()
            session.add(m.WorkspaceSetting(**defaults))
            await session.commit()

            after = await all_table_counts(session)
            print("清空后行数:")
            for name, count in sorted(after.items()):
                print(f"  {name:<24} {count}")
            unexpected = {name: count for name, count in after.items() if count and name not in KEEP_NONEMPTY}
            if unexpected:
                print("警告：预期为空但仍有数据的表:", unexpected)
            if after.get("workspace_settings") != 1 or after.get("credentials") != 1:
                print("警告：系统必需配置行数异常")
    finally:
        await dispose_engine()
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="恢复数据库到最初未使用状态")
    parser.add_argument("--confirm", action="store_true", help="实际执行清库（默认仅预演）")
    parser.add_argument("--purge-builtin-tools", action="store_true", help="连平台内置工具定义一并清空")
    args = parser.parse_args()
    raise SystemExit(asyncio.run(reset(confirm=args.confirm, purge_builtin_tools=args.purge_builtin_tools)))
