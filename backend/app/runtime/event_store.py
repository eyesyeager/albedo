import json
import time
from typing import Any

from app.core.redis_client import get_redis
from app.services.system_config import limit as config_limit

_MEMORY: dict[str, tuple[float, list[dict[str, Any]]]] = {}


def ttl_seconds() -> int:
    return int(config_limit("eventTtlSeconds", 600))


def _key(run_id: str) -> str:
    return f"albedo:agui:events:{run_id}"


async def append_events(run_id: str, events: list[dict[str, Any]]) -> None:
    if not events:
        return
    try:
        client = get_redis()
        key = _key(run_id)
        await client.rpush(key, *[json.dumps(event, ensure_ascii=False) for event in events])
        await client.expire(key, ttl_seconds())
    except Exception:  # noqa: BLE001
        _, current = _MEMORY.get(run_id, (0.0, []))
        _MEMORY[run_id] = (time.time() + ttl_seconds(), current + events)


async def load_events(run_id: str) -> list[dict[str, Any]]:
    try:
        rows = await get_redis().lrange(_key(run_id), 0, -1)
        return [json.loads(row) for row in rows]
    except Exception:  # noqa: BLE001
        expires, events = _MEMORY.get(run_id, (0.0, []))
        return list(events) if expires > time.time() else []
