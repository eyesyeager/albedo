"""把切换 COS 之前上传的本地 Skill 包批量迁移到对象存储（幂等）。

用法：
    .venv/bin/python scripts/migrate_skills_to_cos.py [--dry-run]

只上传缺失对象并回填 skill_versions 的相对 key 与 COS URI，不删除任何本地文件。
重复执行时已迁移过的版本仍会重新上传（对象内容不可变），可用 --dry-run 先核对。
"""
import argparse
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from sqlalchemy import select  # noqa: E402

from app.core.config import get_settings  # noqa: E402
from app.db import models as m  # noqa: E402
from app.db.session import SessionLocal  # noqa: E402
from app.services.skill_package import package_key  # noqa: E402
from app.services.skill_storage import get_skill_storage  # noqa: E402


def derive_key(row: m.SkillVersion) -> str:
    if row.package_key:
        return str(row.package_key)
    name = Path(str(row.file_path or "")).name
    if not name:
        return ""
    return package_key(row.skill_id, row.version, name)


def local_candidates(row: m.SkillVersion, settings, key: str) -> list[Path]:
    paths: list[Path] = []
    if row.file_path and not str(row.file_path).startswith("cos://"):
        paths.append(Path(str(row.file_path)))
    if key:
        paths.append(Path(settings.skill_dir) / key)
    return paths


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="只列出将要迁移的包，不写入")
    args = parser.parse_args()

    settings = get_settings()
    storage = get_skill_storage()
    if storage.backend != "cos":
        print(f"当前 Skill 存储后端为 {storage.backend}；请先设置 SKILL_STORAGE=cos 并配置 COS 凭据")
        return 1

    migrated = skipped = failed = 0
    async with SessionLocal() as session:
        rows = (await session.scalars(select(m.SkillVersion))).all()
        for row in rows:
            key = derive_key(row)
            if not key:
                print(f"✗ {row.skill_id}/{row.version}：缺少 package_key 且无法从文件名推导")
                failed += 1
                continue
            source = next((path for path in local_candidates(row, settings, key) if path.is_file()), None)
            if source is None:
                print(f"- 跳过 {key}：未找到本地包（可能已在 COS）")
                skipped += 1
                continue
            if args.dry_run:
                print(f"[dry-run] {source} -> {storage.uri(key)}")
                continue
            try:
                uri = await asyncio.to_thread(storage.put, key, source.read_bytes())
                row.package_key = key
                row.file_path = uri
                migrated += 1
                print(f"✓ {key} -> {uri}")
            except Exception as exc:  # noqa: BLE001
                failed += 1
                print(f"✗ {key}：{exc}")
        if not args.dry_run:
            await session.commit()
    print(f"完成：迁移 {migrated}，跳过 {skipped}，失败 {failed}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
