import asyncio
import logging
from typing import Any

from langgraph.checkpoint.memory import InMemorySaver

from app.core.config import get_settings

logger = logging.getLogger("albedo.runtime.checkpointer")

_saver: Any | None = None
_lock = asyncio.Lock()
_backend = "uninitialized"

# 远端 Redis 在长连接空闲或网络抖动后可能读超时；限制单次读写耗时并开启 TCP keepalive，
# 让连接被及时回收重建，而不是让一次 Run 卡住到上游超时。
CONNECTION_ARGS: dict[str, Any] = {
    "socket_keepalive": True,
    "socket_connect_timeout": 5,
    "socket_timeout": 5,
    "retry_on_timeout": True,
    "health_check_interval": 30,
}


def is_checkpointer_error(exc: BaseException) -> bool:
    """判断异常是否来自 checkpointer 存储层（Redis 连接/超时/协议错误）。

    只匹配 Redis 客户端自身的异常，以及读取会话状态时的超时；
    上游模型与工具的网络异常不会被误判，避免重复执行一次 Run。
    """
    module = type(exc).__module__ or ""
    if module.startswith(("redis", "hiredis", "langgraph.checkpoint.redis")):
        return True
    return isinstance(exc, TimeoutError)


async def _close_saver(saver: Any | None) -> None:
    if saver is None:
        return
    closer = getattr(saver, "aclose", None) or getattr(saver, "close", None)
    if closer is None:
        return
    try:
        result = closer()
        if asyncio.iscoroutine(result):
            await result
    except Exception as exc:  # noqa: BLE001
        logger.warning("关闭 checkpointer 连接失败：%s", exc)


async def get_checkpointer() -> Any:
    global _saver, _backend
    if _saver is not None:
        return _saver
    async with _lock:
        if _saver is not None:
            return _saver
        settings = get_settings()
        if settings.checkpoint_backend == "redis":
            try:
                from langgraph.checkpoint.redis.aio import AsyncRedisSaver

                saver = AsyncRedisSaver(redis_url=settings.redis_url, connection_args=dict(CONNECTION_ARGS))
                await saver.asetup()
                _saver = saver
                _backend = "redis"
                logger.info("runtime checkpointer ready: redis")
                return _saver
            except Exception as exc:  # noqa: BLE001
                logger.warning("runtime checkpointer redis unavailable, fallback to memory: %s", exc)
        _saver = InMemorySaver()
        _backend = "memory"
        return _saver


async def degrade_to_memory(reason: str) -> Any:
    """Redis 运行期失效时切换到进程内 checkpointer，并释放旧连接池。"""
    global _saver, _backend
    async with _lock:
        if _backend == "memory" and _saver is not None:
            return _saver
        previous = _saver
        _saver = InMemorySaver()
        _backend = "memory"
        logger.warning("runtime checkpointer degraded to memory: %s", reason)
    await _close_saver(previous)
    return _saver


async def close_checkpointer() -> None:
    """应用关闭时释放 checkpointer 连接池，避免 Redis 连接泄漏。"""
    global _saver, _backend
    async with _lock:
        previous = _saver
        _saver = None
        _backend = "uninitialized"
    await _close_saver(previous)


def backend_name() -> str:
    return _backend
