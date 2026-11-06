import re
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, UploadFile
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_session
from app.core.config import get_settings
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
from app.services.skill_package import inspect_zip
from app.services.system_config import get_configs, limit as config_limit

router = APIRouter(tags=["skills"])


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


def save_package(skill_id: str, version: str, file_name: str, content: bytes) -> str:
    target = Path(get_settings().skill_dir) / skill_id / version / Path(file_name).name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(content)
    return str(target)


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
    instructions: str = Form(...),
    fileName: str = Form(...),
    sizeBytes: int = Form(...),
    file: UploadFile = File(...),
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    display_name = validate_name(name, "Skill 展示名称")
    guide = validate_blank(instructions, "使用说明为必填，请填写功能、使用步骤与系统要求")
    config = await get_configs(db)
    skill_defaults = config["defaults"]["skill"]
    first_version = str(skill_defaults["firstVersion"])
    content, package = await read_upload(file, fileName, sizeBytes)
    label = size_label(len(content))
    skill_id = new_id("skill-")
    stamp = now_iso()
    path = save_package(skill_id, first_version, fileName, content)
    row = m.Skill(
        id=skill_id,
        name=display_name,
        description=description_of(guide),
        instructions=guide.strip(),
        version=first_version,
        file_name=fileName,
        file_size=label,
        size_bytes=len(content),
        referenced_by=0,
        verified=True,
        created_at=stamp,
        updated_at=stamp,
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
    ))
    await db.flush()
    entity = await skill_to_dict(db, row)
    return await commit_write(db, entity, "上传 Skill", f"上传「{display_name}」{first_version}，包体积 {label}", resource_id=skill_id)


@router.post("/skills/{skill_id}/versions", response_model=WriteResponse, status_code=201, summary="上传 Skill 新版本")
async def create_skill_version(
    skill_id: str,
    name: str = Form(...),
    instructions: str = Form(...),
    fileName: str = Form(...),
    sizeBytes: int = Form(...),
    file: UploadFile = File(...),
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.Skill, skill_id)
    guide = validate_blank(instructions, "使用说明为必填，请填写功能、使用步骤与系统要求")
    content, package = await read_upload(file, fileName, sizeBytes)
    existing = (await db.scalars(select(m.SkillVersion).where(m.SkillVersion.skill_id == skill_id))).all()
    match = re.match(r"^v(\d+)\.(\d+)\.(\d+)$", row.version)
    version = f"v{match.group(1)}.{int(match.group(2)) + 1}.0" if match else f"v1.{len(existing) + 1}.0"
    if any(item.version == version for item in existing):
        raise ApiException("duplicate_identifier", "该版本号已存在，请使用新的版本号", 409)
    label = size_label(len(content))
    stamp = now_iso()
    path = save_package(skill_id, version, fileName, content)
    row.name = name.strip() or row.name
    row.description = description_of(guide)
    row.instructions = guide.strip()
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
    ))
    await db.flush()
    entity = await skill_to_dict(db, row)
    return await commit_write(
        db, entity, "上传 Skill 新版本",
        f"「{row.name}」发布 {version}，历史版本保留，已有 Agent 不自动升级", resource_id=skill_id,
    )


@router.get("/skills/{skill_id}", response_model=Skill, summary="Skill 详情")
async def get_skill(
    skill_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    return await skill_to_dict(db, await get_or_404(db, m.Skill, skill_id))
