import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.public import agui_router, public_router
from app.api.v1 import v1_router
from app.core.config import get_settings
from app.core.errors import install_error_handlers
from app.core.logging import configure_logging
from app.core.redis_client import close_redis
from app.db.session import SessionLocal, dispose_engine
from app.runtime.mcp_loader import close_all_sessions
from app.middleware.request_id import RequestIdMiddleware
from app.services.system_config import get_configs

logger = logging.getLogger("albedo.startup")


@asynccontextmanager
async def lifespan(_: FastAPI):
    try:
        async with SessionLocal() as session:
            await get_configs(session)
    except Exception as exc:  # 数据库尚未初始化时仍允许启动健康检查
        logger.warning("启动时未能加载系统配置，将使用初始化默认值：%s", exc)
    yield
    await close_all_sessions()
    await close_redis()
    await dispose_engine()


configure_logging()

app = FastAPI(
    title="Albedo Management API",
    version="1.0.0",
    description="albedo 智能体开发平台管理面与对外 API（M0–M5b）",
    lifespan=lifespan,
)

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
