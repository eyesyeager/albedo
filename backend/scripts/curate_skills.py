"""Skill 资源治理：清理测试残留，并把真实 Skill 升级为三级披露 + 迁移到 COS。

判定规则（保守，全部满足才删）：
  保留：被任意 Agent 挂载的 Skill（agent_mounts.kind='skill'）
  清理：未被挂载 且 名称是测试占位 且 包文件名是测试占位 且 指令长度 < 200
  其余：列为「待确认」，脚本一律不动

对保留的 Skill 会：
  1. 从本地 ZIP 解析 SKILL.md 的 frontmatter.description 作为 L1 触发面、正文作为 L2
  2. 回填 skills.description / trigger_description / instructions 与
     skill_versions.instructions / entry / frontmatter / package_key
  3. 上传整个包到 COS，file_path 改为 cos:// URI
  4. 回填 agent_mounts.skill_version，完成版本绑定

用法：
  .venv/bin/python scripts/curate_skills.py --dry-run
  .venv/bin/python scripts/curate_skills.py

幂等：重复执行会重新解析并覆盖同名 COS 对象（内容不可变）。
"""
import argparse
import json
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

from sqlalchemy import create_engine, delete, select, update  # noqa: E402
from sqlalchemy.orm import Session  # noqa: E402

from app.core.config import get_settings  # noqa: E402
from app.db import models as m  # noqa: E402
from app.services.skill_package import inspect_zip, package_key  # noqa: E402
from app.services.skill_storage import get_skill_storage  # noqa: E402

JUNK_NAMES = frozenset({"测试 Skill", "PDF 处理"})
JUNK_FILES = frozenset({"demo-skill.zip", "pdf-processing.zip"})


def bmp(text: str) -> str:
    """MySQL 列为 utf8mb3，无法写入 emoji 等非 BMP 字符，写入前剔除。"""
    return "".join(char for char in text if ord(char) <= 0xFFFF)


def classify(db: Session):
    rows = db.scalars(select(m.Skill)).all()
    mounts = db.scalars(select(m.AgentMount).where(m.AgentMount.kind == "skill")).all()
    referenced = {item.resource_id for item in mounts}
    keep, junk, unknown = [], [], []
    for row in rows:
        if row.id in referenced:
            keep.append(row)
        elif (
            row.name in JUNK_NAMES
            and row.file_name in JUNK_FILES
            and len(str(row.instructions or "")) < 200
        ):
            junk.append(row)
        else:
            unknown.append(row)
    return keep, junk, unknown


def local_package(version: m.SkillVersion, skill: m.Skill, settings) -> Path | None:
    candidates: list[Path] = []
    if version.file_path and not str(version.file_path).startswith("cos://"):
        candidates.append(Path(str(version.file_path)))
    key = version.package_key or package_key(skill.id, version.version, skill.file_name or "")
    if key:
        candidates.append(Path(settings.skill_dir) / key)
    return next((path for path in candidates if path.is_file()), None)


def backup_junk(junk, settings) -> str:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    directory = Path(settings.export_dir).parent / "backups"
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / f"skills-cleanup-{stamp}.json"
    payload = [
        {
            "id": row.id,
            "name": row.name,
            "version": row.version,
            "file_name": row.file_name,
            "instructions": str(row.instructions or "")[:200],
            "created_at": row.created_at,
        }
        for row in junk
    ]
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return str(target)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="只输出计划，不修改任何数据")
    args = parser.parse_args()

    settings = get_settings()
    storage = get_skill_storage()
    if storage.backend != "cos":
        print(f"✗ 当前 Skill 存储后端是 {storage.backend}，请先配置 COS 凭据再迁移。")
        return 2

    engine = create_engine(settings.database_url.replace("mysql+aiomysql", "mysql+pymysql"))
    # 关闭 autoflush：所有 UPDATE 在 commit 时一次性发出，避免循环中途因单条数据
    # 触发 flush 失败、留下「COS 已上传但 DB 未提交」的中间状态。
    with Session(engine, autoflush=False) as db:
        keep, junk, unknown = classify(db)
        print(f"审核：共 {len(keep) + len(junk) + len(unknown)} 个 Skill → 保留 {len(keep)} / 清理 {len(junk)} / 待确认 {len(unknown)}")
        for row in keep:
            print(f"  [保留] {row.id} | {row.name}")
        for row in junk:
            print(f"  [清理] {row.id} | {row.name} | {row.file_name}")
        for row in unknown:
            print(f"  [待确认，未动] {row.id} | {row.name} | {row.file_name}")
        if unknown:
            print("存在待确认项，请先人工核对；脚本不会删除它们。")

        if args.dry_run:
            print("dry-run：未修改数据库、本地文件与 COS。")
            return 0

        backup_path = backup_junk(junk, settings)
        print(f"清理清单已备份：{backup_path}")

        for row in junk:
            db.execute(delete(m.SkillVersion).where(m.SkillVersion.skill_id == row.id))
            db.execute(delete(m.AgentMount).where(
                m.AgentMount.kind == "skill", m.AgentMount.resource_id == row.id
            ))
            db.delete(row)
            shutil.rmtree(Path(settings.skill_dir) / row.id, ignore_errors=True)
            try:
                storage.delete_prefix(f"{row.id}/")
            except Exception as exc:  # noqa: BLE001
                print(f"  ! COS 清理跳过 {row.id}：{type(exc).__name__}")
        print(f"已清理 {len(junk)} 个测试残留 Skill")

        upgraded = 0
        for skill in keep:
            versions = db.scalars(
                select(m.SkillVersion).where(m.SkillVersion.skill_id == skill.id)
            ).all()
            latest = versions[-1] if versions else None
            trigger = ""
            body = ""
            for version in versions:
                path = local_package(version, skill, settings)
                if path is None:
                    print(f"  ! 跳过 {skill.id}/{version.version}：找不到本地包")
                    continue
                content = path.read_bytes()
                info = inspect_zip(content)
                if not info["hasSkillMd"]:
                    print(f"  ! 跳过 {skill.id}/{version.version}：包内无 SKILL.md")
                    continue
                trigger = bmp(str(info["frontmatter"].get("description") or "").strip())[:1024]
                body = bmp(info["body"])
                frontmatter = {str(k): bmp(str(v)) for k, v in (info["frontmatter"] or {}).items()}
                key = version.package_key or package_key(skill.id, version.version, skill.file_name or "")
                uri = storage.put(key, content)
                version.instructions = body
                version.entry = bmp(info["entry"])
                version.package_key = key
                version.frontmatter = frontmatter or None
                version.file_path = uri
                print(f"  ✓ {skill.id}/{version.version} -> {uri}")
            if not trigger:
                continue
            skill.description = trigger
            skill.trigger_description = trigger
            skill.instructions = body
            if latest is not None:
                db.execute(
                    update(m.AgentMount)
                    .where(m.AgentMount.kind == "skill", m.AgentMount.resource_id == skill.id)
                    .values(skill_version=latest.version)
                )
            upgraded += 1
        db.commit()
        print(f"已升级并迁移 {upgraded} 个真实 Skill（L1 触发面 / L2 版本正文 / L3 COS 包 / 挂载版本绑定）")
    engine.dispose()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
