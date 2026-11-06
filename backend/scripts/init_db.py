import asyncio
import subprocess
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

from app.db.seed import seed_workspace  # noqa: E402
from app.db.session import SessionLocal, dispose_engine  # noqa: E402

BASELINE_COUNTS = {
    "workspace_settings": 1,
    "system_configs": 6,
    "builtin_tools": 6,
}


def run_alembic(*arguments: str) -> None:
    subprocess.run([sys.executable, "-m", "alembic", *arguments], cwd=BACKEND_ROOT, check=True)


async def main() -> None:
    versions = [path for path in (BACKEND_ROOT / "alembic" / "versions").glob("*.py") if not path.name.startswith("__")]
    if not versions:
        print("[1/4] 生成初始 Alembic 迁移（对比空库与声明式模型）", flush=True)
        run_alembic("revision", "--autogenerate", "-m", "initial schema")

    print("[2/4] 在目标库真实执行 DDL：alembic upgrade head", flush=True)
    run_alembic("upgrade", "head")

    print("[3/4] 在目标库真实执行 DML：写入系统必需配置（不含演示数据）", flush=True)
    async with SessionLocal() as session:
        counts = await seed_workspace(session)

    await dispose_engine()

    print("[4/4] 逐表只读核对", flush=True)
    for table, count in sorted(counts.items()):
        print(f"  {table:<24} {count}")
    for table, expected in BASELINE_COUNTS.items():
        assert counts[table] >= expected, f"{table} 至少应有 {expected} 行，实际 {counts[table]} 行"
    assert counts["credentials"] >= 1, "访问 Key 凭证未写入"
    print("数据库初始化完成：表结构、系统配置与内置工具目录已落库；业务数据由真实操作产生")


if __name__ == "__main__":
    asyncio.run(main())
