"""MCP 连接层：长连接会话复用、工具发现、调用配额/并发/超时与错误判定。

同一个进程内按连接签名复用 MCP ClientSession，避免每次工具调用都重新握手；
所有 MCP 工具调用统一经过 invoke_mcp_tool，保证 QPM、并发、超时和失败判定生效。
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import shlex
import time
from dataclasses import dataclass, field
from typing import Any

from langchain_core.tools import BaseTool, ToolException
from langchain_mcp_adapters.sessions import create_session
from langchain_mcp_adapters.tools import load_mcp_tools
from mcp.client.stdio import get_default_environment
from pydantic import ValidationError

from app.core.errors import ApiException
from app.core.network import assert_public_url
from app.core.ratelimit import allow_qpm
from app.services.system_config import limit

logger = logging.getLogger("albedo.runtime.mcp")

TRANSPORTS = {"streamable_http": "streamable_http", "sse": "sse", "stdio": "stdio"}
DEFAULT_TIMEOUT = 20.0
SESSION_IDLE_TTL = 300.0


def parse_json_object(value: Any) -> dict[str, str]:
    """兼容 dict 与加密字段解密后的 JSON 字符串。"""
    if isinstance(value, dict):
        raw: Any = value
    elif isinstance(value, str) and value.strip():
        try:
            raw = json.loads(value)
        except (json.JSONDecodeError, TypeError):
            return {}
    else:
        return {}
    if not isinstance(raw, dict):
        return {}
    return {str(key): str(item) for key, item in raw.items()}


def stringify_tool_output(value: Any, limit: int = 4000) -> str:
    """把 MCP 工具返回值压平成可展示文本，超长只截断展示、不修改真实数据。"""
    if isinstance(value, str):
        text = value
    elif isinstance(value, (dict, list)):
        try:
            text = json.dumps(value, ensure_ascii=False, default=str)
        except (TypeError, ValueError):
            text = str(value)
    elif hasattr(value, "content"):
        return stringify_tool_output(getattr(value, "content"), limit)
    else:
        text = str(value)
    if len(text) > limit:
        text = f"{text[:limit]}…（已截断，原始长度 {len(text)} 字符）"
    return text


def mcp_output_error(value: Any) -> str | None:
    """识别没有抛异常、但明确标记为失败的工具结果。

    标准 MCP isError=True 已在 load_mcp_tools(handle_tool_errors=False) 中抛异常；
    这里兜底少数服务端返回 ToolMessage(status=error) 或错误内容块的情况。
    """
    if getattr(value, "status", None) == "error":
        return stringify_tool_output(getattr(value, "content", value), 300)
    if isinstance(value, (list, tuple)):
        for block in value:
            if isinstance(block, dict) and (block.get("status") == "error" or block.get("isError") is True):
                return str(block.get("text") or block.get("content") or "MCP 工具返回错误")[:300]
    return None


def build_connection(server: dict[str, Any]) -> dict[str, Any]:
    transport = str(server.get("transport") or "streamable_http")
    if transport == "stdio":
        parts = shlex.split(str(server.get("endpoint") or ""))
        if not parts:
            raise ValueError("stdio MCP 缺少启动命令")
        connection: dict[str, Any] = {"transport": "stdio", "command": parts[0], "args": parts[1:]}
        env = parse_json_object(server.get("env"))
        if env:
            # 只传用户显式声明的变量 + 运行所需的最小环境，避免把整个进程环境泄露给子进程。
            connection["env"] = {**get_default_environment(), **env}
        return connection
    headers = parse_json_object(server.get("headers"))
    return {
        "transport": TRANSPORTS.get(transport, "streamable_http"),
        "url": str(server.get("endpoint") or ""),
        "headers": headers,
    }


def allow_private_mcp_urls() -> bool:
    value = limit("mcpAllowPrivateUrls", False)
    return value is True or str(value).strip().lower() in {"1", "true", "yes", "on"}


def effective_timeout(server: dict[str, Any], timeout: float | None = None) -> float:
    if timeout is not None:
        value = float(timeout)
    else:
        value = float(server.get("timeoutSeconds") or server.get("timeout_seconds") or DEFAULT_TIMEOUT)
    return max(3.0, min(600.0, value))


def connection_key(connection: dict[str, Any]) -> str:
    payload = json.dumps(connection, sort_keys=True, ensure_ascii=False, default=str)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


@dataclass
class _SessionEntry:
    key: str
    tools: list[BaseTool]
    close_event: asyncio.Event
    task: asyncio.Task
    last_used: float = field(default_factory=time.monotonic)
    invalidated: bool = False


_POOL: dict[str, _SessionEntry] = {}
_POOL_LOCK = asyncio.Lock()
_KEY_LOCKS: dict[str, asyncio.Lock] = {}
_SEMAPHORES: dict[str, tuple[int, asyncio.Semaphore]] = {}


def _consume_future(future: asyncio.Future) -> None:
    """取走后台任务写入的异常，避免 "Future exception was never retrieved"。"""
    if not future.cancelled():
        future.exception()


async def _key_lock(key: str) -> asyncio.Lock:
    async with _POOL_LOCK:
        return _KEY_LOCKS.setdefault(key, asyncio.Lock())


async def _close_entry(entry: _SessionEntry) -> None:
    entry.invalidated = True
    entry.close_event.set()
    try:
        await asyncio.wait_for(entry.task, timeout=5.0)
    except Exception:  # noqa: BLE001 - 关闭失败不能阻塞调用方
        entry.task.cancel()


async def _drop_entry(key: str) -> None:
    async with _POOL_LOCK:
        entry = _POOL.pop(key, None)
    if entry:
        await _close_entry(entry)


async def _evict_idle() -> None:
    now = time.monotonic()
    async with _POOL_LOCK:
        stale = [key for key, entry in _POOL.items() if now - entry.last_used > SESSION_IDLE_TTL]
        entries = [_POOL.pop(key) for key in stale]
        for key in stale:
            _KEY_LOCKS.pop(key, None)
    for entry in entries:
        await _close_entry(entry)


async def _create_entry(key: str, connection: dict[str, Any], server_name: str, timeout: float) -> _SessionEntry:
    ready: asyncio.Future[list[BaseTool]] = asyncio.get_running_loop().create_future()
    ready.add_done_callback(_consume_future)
    close_event = asyncio.Event()

    async def keeper() -> None:
        try:
            # enter/exit 必须发生在同一个 task 中，anyio 的 cancel scope 才不会报错。
            async with create_session(connection) as session:
                await session.initialize()
                tools = await load_mcp_tools(session, server_name=server_name, handle_tool_errors=False)
                if not ready.done():
                    ready.set_result(list(tools))
                await close_event.wait()
        except BaseException as exc:  # noqa: BLE001 - 后台任务的异常必须回传给等待方
            if not ready.done():
                ready.set_exception(exc)
        finally:
            close_event.set()

    task = asyncio.create_task(keeper())
    try:
        tools = await asyncio.wait_for(asyncio.shield(ready), timeout=timeout)
    except BaseException:
        close_event.set()
        ready.cancel()
        raise
    return _SessionEntry(key=key, tools=tools, close_event=close_event, task=task)


async def _ensure_tools(server: dict[str, Any], connection: dict[str, Any], timeout: float, refresh: bool) -> list[BaseTool]:
    key = connection_key(connection)
    lock = await _key_lock(key)
    async with lock:
        await _evict_idle()
        async with _POOL_LOCK:
            entry = _POOL.get(key)
        if entry is not None and not entry.invalidated and not refresh:
            entry.last_used = time.monotonic()
            return entry.tools
        if entry is not None:
            await _drop_entry(key)
        new_entry = await _create_entry(key, connection, str(server.get("id") or key[:8]), timeout)
        async with _POOL_LOCK:
            _POOL[key] = new_entry
        return new_entry.tools


async def load_server_tools(
    server: dict[str, Any],
    timeout: float | None = None,
    *,
    refresh: bool = False,
    rethrow_validation: bool = False,
) -> list[BaseTool]:
    """发现工具；默认复用进程内长连接，refresh=True 时强制重建会话做真实探测。"""
    try:
        if str(server.get("transport") or "") != "stdio":
            await assert_public_url(str(server.get("endpoint") or ""), allow_private=allow_private_mcp_urls())
        connection = build_connection(server)
        tools = await _ensure_tools(server, connection, effective_timeout(server, timeout), refresh)
        return list(tools)
    except ApiException:
        # 管理面需要把 SSRF / URL 校验失败明确反馈给用户，不能伪装成通用 502。
        if rethrow_validation:
            raise
        logger.error("MCP %s 地址校验失败", server.get("id"))
        return []
    except Exception as exc:  # noqa: BLE001
        # 调用方（管理面网关 / 运行时代理）必须把空列表转成显式错误，不得当作成功。
        logger.error("MCP %s 工具发现失败（%s）：%s", server.get("id"), type(exc).__name__, exc)
        return []


async def invalidate_server(server: dict[str, Any]) -> None:
    """连接类故障后丢弃会话，下一次发现时重新握手。"""
    try:
        await _drop_entry(connection_key(build_connection(server)))
    except Exception as exc:  # noqa: BLE001
        logger.warning("MCP 会话失效处理失败：%s", exc)


async def close_all_sessions() -> None:
    async with _POOL_LOCK:
        entries = list(_POOL.values())
        _POOL.clear()
        _SEMAPHORES.clear()
    for entry in entries:
        await _close_entry(entry)


def session_pool_stats() -> dict[str, int]:
    return {"sessions": len(_POOL), "semaphores": len(_SEMAPHORES)}


def _semaphore(server_id: str, max_concurrency: int) -> asyncio.Semaphore | None:
    if max_concurrency <= 0:
        _SEMAPHORES.pop(server_id, None)
        return None
    cached = _SEMAPHORES.get(server_id)
    if cached is None or cached[0] != max_concurrency:
        # 管理员在面板调整并发上限后立即生效。
        cached = (max_concurrency, asyncio.Semaphore(max_concurrency))
        _SEMAPHORES[server_id] = cached
    return cached[1]


def _failed(duration_ms: int, error: str) -> dict[str, Any]:
    return {"status": "failed", "durationMs": duration_ms, "output": "", "error": error}


async def invoke_mcp_tool(
    server: dict[str, Any],
    tool: BaseTool,
    arguments: dict[str, Any],
    timeout: float | None = None,
) -> dict[str, Any]:
    """统一执行 MCP 工具：QPM 配额 -> 并发上限 -> 调用超时 -> 失败判定。"""
    server_id = str(server.get("id") or "")
    duration = effective_timeout(server, timeout)
    qpm = int(server.get("qpm") or 0)
    max_concurrency = int(server.get("maxConcurrency") or server.get("max_concurrency") or 0)

    if qpm > 0 and server_id and not await allow_qpm(f"mcp:{server_id}", qpm):
        return _failed(0, f"超过 MCP 调用额度 {qpm}/分钟，请稍后重试或调整使用配置")
    semaphore = _semaphore(server_id, max_concurrency)

    started = time.perf_counter()

    async def run() -> Any:
        return await asyncio.wait_for(tool.ainvoke(arguments), timeout=duration)

    try:
        value = await (run() if semaphore is None else _with_semaphore(semaphore, run))
    except asyncio.TimeoutError:
        await invalidate_server(server)
        return _failed(int((time.perf_counter() - started) * 1000), f"调用超时：超过 {int(duration)} 秒未返回")
    except Exception as exc:  # noqa: BLE001 - 上游异常需要原样反馈给测试面板
        # 业务/参数错误不应打断长连接，连接类故障才重建会话。
        if not isinstance(exc, (ToolException, ValidationError)):
            await invalidate_server(server)
        return _failed(int((time.perf_counter() - started) * 1000), f"{type(exc).__name__}: {str(exc)[:300]}")

    error = mcp_output_error(value)
    if error:
        return _failed(int((time.perf_counter() - started) * 1000), error)
    return {
        "status": "success",
        "durationMs": int((time.perf_counter() - started) * 1000),
        "output": stringify_tool_output(value),
        "error": None,
    }


async def _with_semaphore(semaphore: asyncio.Semaphore, runner: Any) -> Any:
    async with semaphore:
        return await runner()
