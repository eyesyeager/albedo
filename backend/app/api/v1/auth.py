import secrets

from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import optional_session
from app.core.config import get_settings
from app.core.errors import ApiException
from app.core.redis_client import try_delete, try_get_count, try_increment
from app.core.security import hash_secret, iso_after, new_session_id, now_iso, verify_secret
from app.db import models as m
from app.db.session import get_db
from app.schemas.requests import LoginRequest
from app.schemas.responses import LoginResponse, LogoutResponse, SessionResponse
from app.services.audit import audit

router = APIRouter(tags=["auth"])


def _rate_key(request: Request) -> str:
    host = request.client.host if request.client else "unknown"
    return f"albedo:auth:fail:{host}"


async def _fail(db: AsyncSession, request: Request, message: str, *, code: str, status: int) -> None:
    settings = get_settings()
    await try_increment(_rate_key(request), settings.login_lock_seconds)
    await audit(db, "登录失败", message, status="failed")
    await db.commit()
    raise ApiException(code, message, status)


@router.post("/auth/login", response_model=LoginResponse, summary="访问 Key 登录")
async def login(payload: LoginRequest, request: Request, response: Response, db: AsyncSession = Depends(get_db)) -> dict:
    settings = get_settings()
    rate_key = _rate_key(request)
    if await try_get_count(rate_key) >= settings.login_max_failures:
        await audit(db, "登录失败", "连续失败触发短时限流，请求被拒绝", status="failed")
        await db.commit()
        raise ApiException("rate_limit_exceeded", "尝试次数过多，请稍后再试", 429)

    key = payload.key.strip()
    if len(key) < 12:
        await _fail(db, request, "访问 Key 长度不足 12 个字符", code="validation_error", status=400)

    credential = await db.scalar(select(m.Credential).where(m.Credential.type == "management_access_key"))
    hashed = credential.hash if credential else hash_secret("invalid-placeholder-key")
    if not credential or not verify_secret(hashed, key):
        await _fail(db, request, "访问 Key 校验不通过", code="invalid_api_key", status=401)

    await try_delete(rate_key)
    stamp = now_iso()
    session_id = new_session_id()
    db.add(m.Session(
        id=session_id,
        user_id="owner",
        created_at=stamp,
        idle_expires_at=iso_after(hours=settings.session_idle_hours),
        absolute_expires_at=iso_after(hours=settings.session_absolute_hours),
    ))
    log = await audit(db, "登录成功", "管理界面访问 Key 校验通过")
    await db.commit()

    csrf_token = secrets.token_urlsafe(24)
    response.set_cookie(
        key=settings.session_cookie_name,
        value=session_id,
        httponly=True,
        secure=settings.cookie_secure,
        samesite="lax",
        max_age=settings.session_absolute_hours * 3600,
        path="/",
    )
    response.set_cookie(
        key="albedo_csrf",
        value=csrf_token,
        httponly=False,
        secure=settings.cookie_secure,
        samesite="lax",
        max_age=settings.session_absolute_hours * 3600,
        path="/",
    )
    workspace = await db.get(m.WorkspaceSetting, 1)
    return {
        "authenticated": True,
        "workspaceName": workspace.workspace_name if workspace else "",
        "log": log,
    }


@router.post("/auth/logout", response_model=LogoutResponse, summary="退出登录")
async def logout(request: Request, response: Response, db: AsyncSession = Depends(get_db)) -> dict:
    settings = get_settings()
    session = await optional_session(request, db)
    log = None
    if session is not None:
        session.revoked_at = now_iso()
        log = await audit(db, "退出登录", "当前管理会话已结束")
        await db.commit()
    response.delete_cookie(settings.session_cookie_name, path="/")
    response.delete_cookie("albedo_csrf", path="/")
    return {"ok": True, "log": log}


@router.get("/auth/session", response_model=SessionResponse, summary="查询当前会话")
async def session_info(request: Request, db: AsyncSession = Depends(get_db)) -> dict:
    session = await optional_session(request, db)
    if session is None:
        return {"authenticated": False}
    workspace = await db.get(m.WorkspaceSetting, 1)
    return {"authenticated": True, "workspaceName": workspace.workspace_name if workspace else ""}
