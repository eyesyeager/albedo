import asyncio
import logging
import shlex
from typing import Any

from langchain_core.tools import BaseTool
from langchain_mcp_adapters.client import MultiServerMCPClient

from app.core.network import assert_public_url

logger = logging.getLogger("albedo.runtime.mcp")

TRANSPORTS = {"streamable_http": "streamable_http", "sse": "sse", "stdio": "stdio"}


def build_connection(server: dict[str, Any]) -> dict[str, Any]:
    transport = str(server.get("transport") or "streamable_http")
    headers = server.get("headers") or {}
    if transport == "stdio":
        parts = shlex.split(str(server.get("endpoint") or ""))
        if not parts:
            raise ValueError("stdio MCP 缺少启动命令")
        return {"transport": "stdio", "command": parts[0], "args": parts[1:]}
    return {
        "transport": TRANSPORTS.get(transport, "streamable_http"),
        "url": str(server.get("endpoint") or ""),
        "headers": headers if isinstance(headers, dict) else {},
    }


async def load_server_tools(server: dict[str, Any], timeout: float = 20.0) -> list[BaseTool]:
    try:
        if str(server.get("transport") or "") != "stdio":
            await assert_public_url(str(server.get("endpoint") or ""))
        client = MultiServerMCPClient({str(server["id"]): build_connection(server)})
        return list(await asyncio.wait_for(client.get_tools(server_name=str(server["id"])), timeout=timeout))
    except Exception as exc:  # noqa: BLE001
        # 调用方（管理面网关 / 运行时代理）必须把空列表转成显式错误，不得当作成功。
        logger.error("MCP %s 工具发现失败（%s）：%s", server.get("id"), type(exc).__name__, exc)
        return []
