"""Skill 治理结果自检：数据层 / COS 对象 / 运行时 L1-L2-L3 链路。

用法：cd backend && .venv/bin/python scripts/verify_skills.py [agent_id]
默认检查 agt-f9qqpv（当前唯一挂载了 Skill 的智能体）。
"""
import asyncio
import json
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

from sqlalchemy import create_engine, select  # noqa: E402
from sqlalchemy.orm import Session  # noqa: E402

from app.core.config import get_settings  # noqa: E402
from app.db import models as m  # noqa: E402
from app.db.session import SessionLocal, dispose_engine  # noqa: E402
from app.runtime import tools as runtime_tools  # noqa: E402
from app.runtime.prompts import render_system_prompt  # noqa: E402
from app.services.runtime_snapshot import build_runtime_snapshot  # noqa: E402


def check_data(engine, agent_id: str) -> bool:
    with Session(engine) as db:
        skills = db.query(m.Skill).all()
        versions = db.query(m.SkillVersion).all()
        mounts = db.query(m.AgentMount).filter(
            m.AgentMount.agent_id == agent_id, m.AgentMount.kind == "skill"
        ).all()
        trigger_ok = sum(1 for s in skills if (s.trigger_description or "").strip())
        cos_ok = sum(1 for v in versions if str(v.file_path or "").startswith("cos://"))
        key_ok = sum(1 for v in versions if (v.package_key or "").strip())
        body_ok = sum(1 for v in versions if len(str(v.instructions or "")) > 200)
        pinned = sum(1 for x in mounts if (x.skill_version or "").strip())
        print(f"数据层：skills={len(skills)} versions={len(versions)} mounts={len(mounts)}")
        print(f"  L1 触发面非空 {trigger_ok}/{len(skills)} | L2 版本正文 {body_ok}/{len(versions)}")
        print(f"  L3 相对 key {key_ok}/{len(versions)} | COS URI {cos_ok}/{len(versions)} | 挂载版本绑定 {pinned}/{len(mounts)}")
        return bool(skills) and trigger_ok == len(skills) and body_ok == len(versions) \
            and key_ok == len(versions) and cos_ok == len(versions) and pinned == len(mounts)


def check_cos(settings) -> bool:
    from qcloud_cos import CosConfig, CosS3Client

    client = CosS3Client(CosConfig(
        Region=settings.cos_region, SecretId=settings.cos_secret_id,
        SecretKey=settings.cos_secret_key, Scheme=settings.cos_scheme,
        Timeout=settings.cos_timeout_seconds,
    ))
    response = client.list_objects(Bucket=settings.cos_bucket, Prefix=settings.cos_prefix + "/", MaxKeys=100)
    items = response.get("Contents") or []
    if isinstance(items, dict):
        items = [items]
    print(f"对象存储：{settings.cos_prefix}/ 下 {len(items)} 个包（bucket={settings.cos_bucket}, region={settings.cos_region}）")
    return len(items) > 0


async def check_runtime(agent_id: str) -> bool:
    async with SessionLocal() as db:
        agent = await db.get(m.Agent, agent_id)
        if agent is None:
            print(f"✗ 找不到 Agent {agent_id}")
            return False
        model = None
        if agent.default_model_id:
            model = await db.get(m.ModelResource, agent.default_model_id)
        if model is None:
            model = (await db.scalars(select(m.ModelResource).limit(1))).first()
        if model is None:
            model = m.ModelResource(id="mdl-verify", name="verify-model", enabled=True)
        snapshot = await build_runtime_snapshot(db, agent, model)
    await dispose_engine()
    skills = snapshot["resolvedMounts"]["skill"]
    catalog = snapshot.get("skillCatalog") or []
    rendered, _ = render_system_prompt(snapshot, {})
    print(f"运行时：L1 清单 {len(catalog)} 条 | system prompt 已注入={'可用 Skills' in rendered}")
    print(f"  L2 正文字数 {[len(s['instructions']) for s in skills]}")
    print(f"  L3 包路径可用 {sum(1 for s in skills if s['packagePath'])}/{len(skills)} | "
          f"资源 {[len(s['resources']) for s in skills]} | 脚本 {[len(s['scripts']) for s in skills]}")
    if not skills or not catalog or "可用 Skills" not in rendered:
        return False

    first = skills[0]
    member = next((r for r in first["resources"] if not r.endswith("SKILL.md")), None)
    if member is None and first["resources"]:
        member = first["resources"][0]
    if member:
        result = json.loads(await runtime_tools.skill_resource_runner(snapshot)(
            skillId=first["id"], path=member
        ))
        print(f"  L3 实读 {first['id']} / {member} -> {result['status']}, {result.get('chars', 0)} 字符")
        if result["status"] != "loaded":
            return False
    return True


def main() -> int:
    agent_id = sys.argv[1] if len(sys.argv) > 1 else "agt-f9qqpv"
    settings = get_settings()
    engine = create_engine(settings.database_url.replace("mysql+aiomysql", "mysql+pymysql"))
    data_ok = check_data(engine, agent_id)
    engine.dispose()
    cos_ok = check_cos(settings)
    runtime_ok = asyncio.run(check_runtime(agent_id))
    print("结论：", "Skill 三级披露链路已打通" if data_ok and cos_ok and runtime_ok else "存在未通过项")
    return 0 if data_ok and cos_ok and runtime_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
