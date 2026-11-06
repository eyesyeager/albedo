from fastapi import Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.errors import ApiException
from app.core.security import iso_after, now_iso
from app.db import models as m
from app.db.session import get_db

_UNAUTHORIZED = ApiException("invalid_api_key", "登录状态已失效，请重新登录", 401)


async def optional_session(request: Request, db: AsyncSession = Depends(get_db)) -> m.Session | None:
    session_id = request.cookies.get(get_settings().session_cookie_name)
    if not session_id:
        return None
    record = await db.get(m.Session, session_id)
    if record is None or record.revoked_at:
        return None
    now = now_iso()
    if record.idle_expires_at < now or record.absolute_expires_at < now:
        return None
    return record


async def current_session(request: Request, db: AsyncSession = Depends(get_db)) -> m.Session:
    record = await optional_session(request, db)
    if record is None:
        raise _UNAUTHORIZED
    settings = get_settings()
    if settings.csrf_enabled and request.method not in ("GET", "HEAD", "OPTIONS"):
        expected = request.cookies.get("albedo_csrf", "")
        provided = request.headers.get("X-CSRF-Token", "")
        if not expected or provided != expected:
            raise ApiException("invalid_api_key", "CSRF 校验失败，请刷新页面后重试", 403)
    record.idle_expires_at = iso_after(hours=settings.session_idle_hours)
    await db.commit()
    return record
