import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import select

from app.api.public import agui_router, public_router
from app.api.v1 import v1_router
from app.core.config import PUBLIC_DEMO_KEYS, get_settings
from app.core.errors import install_error_handlers
from app.core.logging import configure_logging
from app.core.redis_client import close_redis
from app.core.security import verify_secret
from app.db import models as m
from app.db.session import SessionLocal, dispose_engine
from app.middleware.request_id import RequestIdMiddleware
from app.runtime.checkpointer import close_checkpointer
from app.runtime.mcp_loader import close_all_sessions
from app.services.system_config import get_configs

logger = logging.getLogger("albedo.startup")


def _demo_key_variants() -> set[str]:
    variants: set[str] = set()
    for key in PUBLIC_DEMO_KEYS:
        variants.update({key, key.upper(), key.lower(), key.capitalize()})
    return variants


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings = get_settings()
    try:
        async with SessionLocal() as session:
            await get_configs(session)
            if settings.environment != "dev":
                # seed.py 只在凭据不存在时写入，修改环境变量不会更新已入库 hash。
                # 因此启动时必须直接校验库里的 hash，而不是只看配置值。
                credential = await session.scalar(
                    select(m.Credential).where(m.Credential.type == "management_access_key")
                )
                if credential is not None and any(
                    verify_secret(credential.hash, candidate) for candidate in _demo_key_variants()
                ):
                    raise RuntimeError(
                        "数据库内的管理面访问 Key 仍是公开演示值；修改环境变量不会更新已入库 hash，"
                        "请先在「系统设置 → 访问与安全」中修改后再启动"
                    )
    except RuntimeError:
        raise
    except Exception as exc:
        if settings.environment != "dev":
            raise RuntimeError(f"启动时无法加载系统配置或校验访问 Key：{exc}") from exc
        logger.warning("启动时未能加载系统配置，将使用初始化默认值：%s", exc)
    yield
    await close_all_sessions()
    await close_checkpointer()
    await close_redis()
    await dispose_engine()


configure_logging()

app = FastAPI(
    title="Albedo Management API",
    version="1.0.0",
    description="albedo 智能体开发平台管理面与对外 API",
    lifespan=lifespan,
)


def body_limit_exceeded(declared: str | None, limit: int) -> bool:
    if not declared:
        return False
    try:
        return int(declared) > limit
    except ValueError:
        return False


@app.middleware("http")
async def limit_request_body(request: Request, call_next):
    """按 Content-Length 提前拒绝超大 JSON 请求体，避免内存与上游费用放大。"""
    # Skill 包上传是 multipart，大小由 skillMaxBytes 与解压体积校验在业务层控制，
    # 不套用 JSON 请求体的上限，否则合法的大 Skill 包会被误拦。
    is_multipart = request.headers.get("content-type", "").startswith("multipart/form-data")
    if not is_multipart and body_limit_exceeded(
        request.headers.get("content-length"), get_settings().max_request_body_bytes
    ):
        return JSONResponse(
            status_code=413,
            content={"error": {"code": "payload_too_large", "message": "请求体超过大小限制"}},
        )
    return await call_next(request)


@app.middleware("http")
async def no_store_management_responses(request: Request, call_next):
    """管理面响应一律禁止缓存：常规资源接口与写响应可能含凭据、配置与审计数据。"""
    response = await call_next(request)
    content_type = response.headers.get("content-type", "")
    if request.url.path.startswith("/api/v1/") and "text/event-stream" not in content_type:
        response.headers["Cache-Control"] = "no-store, private"
    return response


app.add_middleware(RequestIdMiddleware)
app.add_middleware(
    CORSMiddleware,
    allow_origins=get_settings().cors_origin_list,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
install_error_handlers(app)

app.include_router(v1_router, prefix="/api/v1")
app.include_router(public_router, prefix="/v1")
app.include_router(agui_router, prefix="/api/agui/v1")


@app.get("/health", tags=["meta"], summary="健康检查")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "albedo-backend"}
