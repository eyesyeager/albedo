import logging
import re
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, UploadFile
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_session
from app.core.errors import ApiException
from app.core.security import new_id, now_iso
from app.db import models as m
from app.db.session import get_db
from app.schemas.domain import Skill
from app.schemas.responses import WriteResponse
from app.services.common import (
    commit_write,
    get_or_404,
    skill_to_dict,
    validate_blank,
    validate_name,
)
from app.services.skill_package import inspect_zip, package_key
from app.services.skill_storage import delete_async, delete_prefix_async, put_async
from app.services.system_config import get_configs, limit as config_limit

router = APIRouter(tags=["skills"])
logger = logging.getLogger("albedo.skills")


async def cleanup_orphan_package(key: str) -> None:
    """数据库写入失败时尽力清理已上传的对象，避免对象存储中留下孤儿包。"""
    try:
        await delete_async(key)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Skill 孤儿包清理失败 key=%s：%s", key, exc)


def max_skill_bytes() -> int:
    return int(config_limit("skillMaxBytes", 100 * 1024 * 1024))


def size_label(size_bytes: int) -> str:
    if size_bytes >= 1024 * 1024:
        return f"{size_bytes / 1024 / 1024:.1f} MB"
    if size_bytes >= 1024:
        return f"{round(size_bytes / 1024)} KB"
    return f"{size_bytes} B"


def description_of(instructions: str) -> str:
    return re.split(r"[。\n]", instructions)[0][:60]


def resolve_instructions(form_text: str, package: dict) -> tuple[str, str, str]:
    """确定 L2 正文与 L1 触发面。

    包内含 SKILL.md 时以包为准（frontmatter.description 是触发面）；
    旧式包无 SKILL.md 时降级为表单手填的使用说明，保持向后兼容。
    """
    body = str(package.get("body") or "").strip()
    frontmatter = package.get("frontmatter") or {}
    trigger = str(frontmatter.get("description") or "").strip()[:1024]
    if package.get("hasSkillMd") and body:
        summary = (trigger or description_of(body))[:1024]
        return body, summary, trigger
    guide = validate_blank(
        form_text,
        "使用说明为必填：请填写功能、使用步骤与系统要求，或上传包含 SKILL.md 的 Skill 包",
    )
    return guide.strip(), description_of(guide), ""


def version_fields(skill_id: str, version: str, file_name: str, package: dict, instructions: str) -> dict:
    """L2/L3 所需的版本级字段：冻结正文、入口、包定位 key、frontmatter。"""
    return {
        "instructions": instructions,
        "entry": str(package.get("entry") or ""),
        "package_key": package_key(skill_id, version, file_name),
        "frontmatter": package.get("frontmatter") or None,
    }


async def read_upload(file: UploadFile, file_name: str, size_bytes: int) -> tuple[bytes, dict]:
    if not file_name.lower().endswith(".zip"):
        raise ApiException("validation_error", "Skill 包必须是 .zip 格式", 400)
    maximum = max_skill_bytes()
    if size_bytes > maximum:
        raise ApiException("payload_too_large", f"Skill 包不能超过 {maximum // (1024 * 1024)} MB", 413)
    content = await file.read()
    if len(content) > maximum:
        raise ApiException("payload_too_large", f"Skill 包不能超过 {maximum // (1024 * 1024)} MB", 413)
    if size_bytes != len(content):
        raise ApiException("validation_error", "声明的文件大小与实际上传字节数不一致，请重新选择文件", 400)
    if not content.startswith(b"PK"):
        raise ApiException("skill_manifest_invalid", "Skill 包不是有效的 ZIP 文件", 422)
    package = inspect_zip(content)
    return content, package


async def save_package(skill_id: str, version: str, file_name: str, content: bytes) -> str:
    """把包写入配置的存储后端，返回存储 URI（COS URI 或本地绝对路径）。"""
    return await put_async(package_key(skill_id, version, file_name), content)


@router.get("/skills", response_model=list[Skill], summary="Skill 列表")
async def list_skills(
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> list[dict]:
    rows = (await db.scalars(select(m.Skill))).all()
    return [await skill_to_dict(db, row) for row in rows]


@router.post("/skills", response_model=WriteResponse, status_code=201, summary="上传 Skill（multipart）")
async def create_skill(
    name: str = Form(...),
    fileName: str = Form(...),
    sizeBytes: int = Form(...),
    file: UploadFile = File(...),
    instructions: str = Form(""),
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    display_name = validate_name(name, "Skill 展示名称")
    config = await get_configs(db)
    skill_defaults = config["defaults"]["skill"]
    first_version = str(skill_defaults["firstVersion"])
    content, package = await read_upload(file, fileName, sizeBytes)
    guide, summary, trigger = resolve_instructions(instructions, package)
    label = size_label(len(content))
    skill_id = new_id("skill-")
    stamp = now_iso()
    key = package_key(skill_id, first_version, fileName)
    path = await save_package(skill_id, first_version, fileName, content)
    try:
        row = m.Skill(
            id=skill_id,
            name=display_name,
            description=summary,
            instructions=guide,
            version=first_version,
            file_name=fileName,
            file_size=label,
            size_bytes=len(content),
            referenced_by=0,
            verified=True,
            created_at=stamp,
            updated_at=stamp,
            trigger_description=trigger,
        )
        db.add(row)
        db.add(m.SkillVersion(
            skill_id=skill_id,
            version=first_version,
            file_size=label,
            file_path=path,
            manifest=package["manifest"] or {"files": package["files"], "uncompressedBytes": package["uncompressedBytes"]},
            note=str(skill_defaults["firstNote"]),
            created_at=stamp,
            **version_fields(skill_id, first_version, fileName, package, guide),
        ))
        await db.flush()
        entity = await skill_to_dict(db, row)
        return await commit_write(
            db, entity, "上传 Skill",
            f"上传「{display_name}」{first_version}，包体积 {label}", resource_id=skill_id,
        )
    except Exception:
        await cleanup_orphan_package(key)
        raise


@router.post("/skills/{skill_id}/versions", response_model=WriteResponse, status_code=201, summary="上传 Skill 新版本")
async def create_skill_version(
    skill_id: str,
    name: str = Form(...),
    fileName: str = Form(...),
    sizeBytes: int = Form(...),
    file: UploadFile = File(...),
    instructions: str = Form(""),
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.Skill, skill_id)
    content, package = await read_upload(file, fileName, sizeBytes)
    guide, summary, trigger = resolve_instructions(instructions, package)
    existing = (await db.scalars(select(m.SkillVersion).where(m.SkillVersion.skill_id == skill_id))).all()
    match = re.match(r"^v(\d+)\.(\d+)\.(\d+)$", row.version)
    version = f"v{match.group(1)}.{int(match.group(2)) + 1}.0" if match else f"v1.{len(existing) + 1}.0"
    if any(item.version == version for item in existing):
        raise ApiException("duplicate_identifier", "该版本号已存在，请使用新的版本号", 409)
    label = size_label(len(content))
    stamp = now_iso()
    key = package_key(skill_id, version, fileName)
    path = await save_package(skill_id, version, fileName, content)
    try:
        row.name = name.strip() or row.name
        row.description = summary
        row.instructions = guide
        row.trigger_description = trigger
        row.version = version
        row.file_name = fileName
        row.file_size = label
        row.size_bytes = len(content)
        row.verified = True
        row.updated_at = stamp
        db.add(m.SkillVersion(
            skill_id=skill_id,
            version=version,
            file_size=label,
            file_path=path,
            manifest=package["manifest"] or {"files": package["files"], "uncompressedBytes": package["uncompressedBytes"]},
            note="新增版本，历史包保留",
            created_at=stamp,
            **version_fields(skill_id, version, fileName, package, guide),
        ))
        await db.flush()
        entity = await skill_to_dict(db, row)
        return await commit_write(
            db, entity, "上传 Skill 新版本",
            f"「{row.name}」发布 {version}，历史版本保留，已有 Agent 不自动升级", resource_id=skill_id,
        )
    except Exception:
        await cleanup_orphan_package(key)
        raise


@router.get("/skills/{skill_id}", response_model=Skill, summary="Skill 详情")
async def get_skill(
    skill_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    return await skill_to_dict(db, await get_or_404(db, m.Skill, skill_id))


@router.delete("/skills/{skill_id}", response_model=WriteResponse, summary="删除 Skill")
async def remove_skill(
    skill_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.Skill, skill_id)
    mounts = (
        await db.scalars(
            select(m.AgentMount).where(m.AgentMount.kind == "skill", m.AgentMount.resource_id == skill_id)
        )
    ).all()
    if mounts:
        names = []
        for mount in mounts:
            agent = await db.get(m.Agent, mount.agent_id)
            if agent is not None:
                names.append(agent.name)
        detail = f"（{'、'.join(names)}）" if names else ""
        raise ApiException(
            "resource_in_use",
            f"该 Skill 已被 {len(mounts)} 个智能体挂载{detail}，请先在智能体的「能力挂载」中取消挂载后再删除",
            409,
        )

    name = row.name
    version_count = int(
        await db.scalar(
            select(func.count()).select_from(m.SkillVersion).where(m.SkillVersion.skill_id == skill_id)
        )
        or 0
    )
    versions = (
        await db.scalars(select(m.SkillVersion).where(m.SkillVersion.skill_id == skill_id))
    ).all()
    # 迁移前创建的版本没有 package_key，只有本地 file_path，仍需兼容清理。
    legacy_paths = [
        Path(str(item.file_path))
        for item in versions
        if not item.package_key and item.file_path and not str(item.file_path).startswith("cos://")
    ]
    await db.execute(delete(m.SkillVersion).where(m.SkillVersion.skill_id == skill_id))
    await db.delete(row)
    result = await commit_write(
        db, None, "删除 Skill", f"删除「{name}」，同时清理 {version_count} 个历史版本与对象存储包",
        resource_id=skill_id,
    )
    await delete_prefix_async(f"{skill_id}/")
    for path in legacy_paths:
        path.unlink(missing_ok=True)
    return result
