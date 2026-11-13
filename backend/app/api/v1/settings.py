from typing import Any

from fastapi import APIRouter, Depends
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_session
from app.services.log_retention import clamp_retention_days, prune_logs
from app.core.errors import ApiException
from app.core.security import encrypt_secret, hash_secret, now_iso, suffix_of, verify_secret
from app.db import models as m
from app.db.seed import to_row
from app.db.session import get_db
from app.schemas.domain import WorkspaceSettings
from app.schemas.requests import AccessKeyDraft, SettingsPatch
from app.schemas.responses import WriteResponse
from app.services.audit import audit
from app.services.snapshot import row_dict
from app.services.system_config import get_configs, update_configs

router = APIRouter(tags=["settings"])


def settings_dict(row: m.WorkspaceSetting) -> dict:
    return row_dict(row, drop={"id", "updated_at"})


@router.get("/settings", response_model=WorkspaceSettings, summary="工作区设置")
async def get_settings_api(
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await db.get(m.WorkspaceSetting, 1)
    if row is None:
        raise ApiException("resource_not_found", "工作区设置未初始化，请先运行 scripts/init_db.py", 404)
    return settings_dict(row)


@router.get("/settings/configs", summary="读取系统配置")
async def get_system_configs(
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict[str, Any]:
    return await get_configs(db)


@router.patch("/settings/configs", summary="更新系统配置")
async def patch_system_configs(
    payload: dict[str, Any],
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict[str, Any]:
    config = await update_configs(db, payload)
    log = await audit(db, "更新系统配置", f"已更新 {len(payload)} 项系统配置并即时生效")
    await db.commit()
    return {"config": config, "log": log}


@router.patch("/settings", response_model=WriteResponse, summary="更新工作区设置")
async def update_settings(
    payload: SettingsPatch,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await db.get(m.WorkspaceSetting, 1)
    if row is None:
        raise ApiException("resource_not_found", "工作区设置未初始化", 404)
    patch = payload.model_dump(exclude_unset=True)
    if patch.get("retentionDays") is not None:
        patch["retentionDays"] = clamp_retention_days(patch["retentionDays"])
    updates = to_row(patch, m.WorkspaceSetting)
    for column, value in updates.items():
        setattr(row, column, value)
    row.updated_at = now_iso()

    main_log = await audit(db, "更新工作区设置", "常规设置与治理策略已保存")
    extra_logs = []
    if "retention_days" in updates:
        result = await prune_logs(db, row.retention_days)
        extra_logs.append(await audit(
            db, "更新日志保留策略",
            f"日志保留周期调整为 {row.retention_days} 天；清理前已将 {result['deleted']} 条日志脱敏归档到 {result['archive'] or '无'}",
        ))
        extra_logs.append(await audit(
            db, "执行日志清理任务",
            f"按 {row.retention_days} 天周期分批清理 {result['deleted']} 条过期日志，任务完成且可审计",
        ))
    await db.commit()
    logs = extra_logs or [main_log]
    return {"settings": settings_dict(row), "log": logs[0], "logs": logs, "entity": None}


@router.post("/settings/access-key", response_model=WriteResponse, summary="修改访问 Key")
async def change_access_key(
    payload: AccessKeyDraft,
    session: m.Session = Depends(current_session),
    db: AsyncSession = Depends(get_db),
) -> dict:
    config = await get_configs(db)
    min_length = int(config["limits"]["accessKeyMinLength"])
    credential = await db.scalar(select(m.Credential).where(m.Credential.type == "management_access_key"))
    if credential is None or not verify_secret(credential.hash, payload.current.strip()):
        raise ApiException("invalid_api_key", "当前访问 Key 不正确", 401)
    if len(payload.next) < min_length:
        raise ApiException("validation_error", f"新 Key 至少需要 {min_length} 个字符", 400)
    if payload.next != payload.confirm:
        raise ApiException("validation_error", "两次输入的新 Key 不一致", 400)
    credential.hash = hash_secret(payload.next)
    credential.cipher = encrypt_secret(payload.next)
    credential.suffix = suffix_of(payload.next)
    credential.rotated_at = now_iso()
    row = await db.get(m.WorkspaceSetting, 1)
    row.access_key_suffix = credential.suffix
    row.updated_at = now_iso()
    await db.execute(update(m.Session).where(m.Session.id != session.id).values(revoked_at=now_iso()))
    log = await audit(db, "修改访问 Key", "旧访问 Key 立即失效，当前会话保持有效")
    await db.commit()
    return {"settings": settings_dict(row), "log": log, "entity": None}
