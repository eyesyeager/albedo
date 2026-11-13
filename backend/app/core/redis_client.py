import asyncio
from typing import Any

import redis.asyncio as redis

from app.core.config import get_settings

_client: redis.Redis | None = None
_local_counters: dict[str, tuple[int, float]] = {}
_local_lock = asyncio.Lock()


def get_redis() -> redis.Redis:
    global _client
    if _client is None:
        _client = redis.Redis.from_url(
            get_settings().redis_url,
            decode_responses=True,
            socket_connect_timeout=2,
            socket_timeout=2,
        )
    return _client


# 只在计数器创建时设置过期时间。若每次 INCR 都 EXPIRE，持续流量会把固定窗口
# 无限续期、计数单调增长，最终让限流键永久处于超限状态。
_INCREMENT_SCRIPT = """
local current = redis.call('INCR', KEYS[1])
if current == 1 then
  redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return current
"""


async def try_increment(key: str, ttl_seconds: int) -> int:
    try:
        client = get_redis()
        count = await client.eval(_INCREMENT_SCRIPT, 1, key, max(1, int(ttl_seconds)))
        return int(count)
    except Exception:
        async with _local_lock:
            loop = asyncio.get_running_loop()
            current, expires_at = _local_counters.get(key, (0, 0.0))
            if expires_at < loop.time():
                current = 0
                expires_at = loop.time() + ttl_seconds
            current += 1
            _local_counters[key] = (current, expires_at)
            return current


_local_locks: dict[str, float] = {}


async def try_setnx(key: str, value: str, ttl_seconds: int) -> bool:
    """分布式幂等锁：仅首次设置成功，Redis 不可用时降级到进程内短 TTL 锁。"""
    ttl = max(1, int(ttl_seconds))
    try:
        return bool(await get_redis().set(key, value, ex=ttl, nx=True))
    except Exception:  # noqa: BLE001
        async with _local_lock:
            loop = asyncio.get_running_loop()
            now = loop.time()
            for stale in [name for name, expires_at in _local_locks.items() if expires_at < now]:
                _local_locks.pop(stale, None)
            if key in _local_locks:
                return False
            _local_locks[key] = now + ttl
            return True


async def try_get_count(key: str) -> int:
    try:
        raw = await get_redis().get(key)
        return int(raw or 0)
    except Exception:
        loop = asyncio.get_running_loop()
        async with _local_lock:
            current, expires_at = _local_counters.get(key, (0, 0.0))
            return current if expires_at >= loop.time() else 0


async def try_delete(key: str) -> None:
    try:
        await get_redis().delete(key)
    except Exception:
        async with _local_lock:
            _local_counters.pop(key, None)


async def try_set(key: str, value: str, ttl_seconds: int) -> None:
    try:
        await get_redis().set(key, value, ex=ttl_seconds)
    except Exception:
        pass


async def try_get(key: str) -> Any:
    try:
        return await get_redis().get(key)
    except Exception:
        return None


async def close_redis() -> None:
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None
