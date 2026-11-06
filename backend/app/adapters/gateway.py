"""外部服务网关：唯一实现，始终发起真实请求。"""

import asyncio
import json
import time
from typing import Any
from urllib.parse import urlparse

import httpx

from app.core.errors import ApiException
from app.core.network import assert_public_url
from app.runtime.mcp_loader import load_server_tools


def is_http_url(value: str) -> bool:
    try:
        parsed = urlparse(value.strip())
        return parsed.scheme in ("http", "https") and bool(parsed.netloc)
    except ValueError:
        return False


def is_json_object(value: str) -> bool:
    if not value.strip():
        return True
    try:
        return isinstance(json.loads(value), dict)
    except (json.JSONDecodeError, TypeError):
        return False


def tool_input_schema(tool: Any) -> dict:
    """读取工具声明的入参 JSON Schema；langchain 工具可能返回完整 schema 或仅 properties。"""
    try:
        schema = getattr(tool, "args_schema", None)
    except Exception:  # noqa: BLE001 - 个别工具实现可能在属性访问时抛错
        schema = None
    if isinstance(schema, dict) and schema:
        if schema.get("properties") is not None:
            return schema
        if all(isinstance(value, dict) for value in schema.values()):
            # 有些客户端只暴露 properties 映射，补成标准 JSON Schema。
            return {"type": "object", "properties": schema}
    if isinstance(schema, type) and hasattr(schema, "model_json_schema"):
        try:
            return schema.model_json_schema()
        except Exception:  # noqa: BLE001
            pass
    try:
        args = getattr(tool, "args", None)
    except Exception:  # noqa: BLE001
        return {}
    if isinstance(args, dict) and args:
        return {"type": "object", "properties": args}
    return {}


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


class ModelGateway:
    async def test_connection(self, draft: dict) -> dict:
        base_url = str(draft.get("baseUrl", "")).strip()
        api_key = str(draft.get("apiKey", "")).strip()
        model_id = str(draft.get("upstreamModelId", "")).strip()
        headers_text = str(draft.get("headers", "") or "")
        if not is_http_url(base_url):
            raise ApiException("upstream_unavailable", "Base URL 不是有效的 HTTP(S) 地址，请检查后重试", 502)
        if not api_key or not model_id:
            raise ApiException("upstream_auth_failed", "上游 API Key 或 Model ID 缺失，鉴权失败", 401)
        if not is_json_object(headers_text):
            raise ApiException("validation_error", "自定义请求头必须是合法的 JSON 对象", 400)
        await assert_public_url(base_url)

        headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
        if headers_text.strip():
            headers.update(json.loads(headers_text))
        timeout = max(5, int(draft.get("timeoutSeconds") or 30))
        embedding = str(draft.get("type") or "chat") == "embedding"
        path = "/embeddings" if embedding else "/chat/completions"
        probe_body = (
            {"model": model_id, "input": ["ping"]}
            if embedding
            else {"model": model_id, "messages": [{"role": "user", "content": "ping"}], "max_tokens": 64, "temperature": 0}
        )
        started = time.perf_counter()
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(timeout)) as client:
                response = await client.post(
                    f"{base_url.rstrip('/')}{path}",
                    headers=headers,
                    json=probe_body,
                )
        except httpx.TimeoutException as exc:
            raise ApiException("upstream_timeout", "上游响应超时，请适当增加超时时间", 504) from exc
        except httpx.HTTPError as exc:
            raise ApiException("upstream_unavailable", "连接测试失败：上游服务不可达，请检查地址与网络", 502) from exc

        latency_ms = int((time.perf_counter() - started) * 1000)
        if response.status_code in (401, 403):
            raise ApiException("upstream_auth_failed", "上游鉴权失败，请检查 API Key 与请求头", 401)
        if response.status_code == 429:
            raise ApiException("rate_limit_exceeded", "上游限流，请稍后重试或检查配额", 429)
        if response.status_code >= 500:
            raise ApiException("upstream_unavailable", f"上游服务异常（HTTP {response.status_code}）", 502)
        if response.status_code >= 400:
            detail = response.text[:200]
            raise ApiException("validation_error", f"上游拒绝了请求（HTTP {response.status_code}）：{detail}", 400)
        try:
            payload = response.json()
            if embedding:
                if not isinstance(payload.get("data"), list) or not payload["data"]:
                    raise ValueError("missing embeddings")
            elif not payload.get("choices"):
                raise ValueError("missing choices")
        except (ValueError, json.JSONDecodeError) as exc:
            raise ApiException("upstream_unavailable", "上游返回结构不合法，与声明的模型类型不匹配", 502) from exc
        return {
            "latencyMs": latency_ms,
            "httpStatus": response.status_code,
            "errorCategory": None,
            "responseSummary": "embeddings 返回结构合法" if embedding else "chat.completion 返回结构合法",
        }


class McpGateway:
    """真实 MCP 工具发现：连接远端服务并把工具原名写入资源池。"""

    async def discover_tools(self, draft: dict) -> list[dict]:
        endpoint = str(draft.get("endpoint", "")).strip()
        transport = str(draft.get("transport", "streamable_http"))
        headers_text = str(draft.get("headers", "") or "")
        if not endpoint:
            raise ApiException("validation_error", "服务地址/命令不能为空", 400)
        if transport != "stdio" and not is_http_url(endpoint):
            raise ApiException("validation_error", "远程 MCP 服务地址必须是合法的 HTTP(S) URL", 400)
        if not is_json_object(headers_text):
            raise ApiException("validation_error", "自定义请求头必须是合法的 JSON 对象", 400)
        headers = json.loads(headers_text) if headers_text.strip() else {}
        if transport != "stdio":
            await assert_public_url(endpoint)

        timeout = max(8.0, min(30.0, float(draft.get("timeoutSeconds") or 15)))
        tools = await load_server_tools({
            "id": "mcp-probe",
            "transport": transport,
            "endpoint": endpoint,
            "headers": headers,
        }, timeout=timeout)
        if not tools:
            raise ApiException(
                "upstream_unavailable",
                "MCP 服务未返回可用工具：连接失败或未开启工具发现，请检查地址与请求头",
                502,
            )
        return [
            {
                "name": str(tool.name),
                "key": str(tool.name),
                "description": str(tool.description or "")[:300],
                "inputSchema": tool_input_schema(tool),
            }
            for tool in tools
        ]

    async def call_tool(
        self,
        server: dict,
        tool_key: str,
        arguments: dict[str, Any],
        timeout: float = 30.0,
    ) -> dict[str, Any]:
        """真实调用单个 MCP 工具；上游失败以 status=failed 返回，由调用方落库。"""
        tools = await load_server_tools(server, timeout=timeout)
        if not tools:
            raise ApiException(
                "upstream_unavailable",
                "MCP 服务不可用：未能发现工具，请先完成连接测试",
                502,
            )
        tool = next((item for item in tools if str(item.name) == tool_key), None)
        if tool is None:
            tool = next((item for item in tools if str(item.name).endswith(tool_key)), None)
        if tool is None:
            raise ApiException(
                "resource_not_found",
                f"MCP 服务未提供工具「{tool_key}」，请重新测试连接刷新工具清单",
                404,
            )

        started = time.perf_counter()
        try:
            value = await asyncio.wait_for(tool.ainvoke(arguments), timeout=timeout)
        except asyncio.TimeoutError:
            return {
                "status": "failed",
                "durationMs": int((time.perf_counter() - started) * 1000),
                "output": "",
                "error": f"调用超时：超过 {int(timeout)} 秒未返回",
            }
        except Exception as exc:  # noqa: BLE001 - 上游异常需要原样反馈给测试面板
            return {
                "status": "failed",
                "durationMs": int((time.perf_counter() - started) * 1000),
                "output": "",
                "error": f"{type(exc).__name__}: {str(exc)[:300]}",
            }
        return {
            "status": "success",
            "durationMs": int((time.perf_counter() - started) * 1000),
            "output": stringify_tool_output(value),
            "error": None,
        }
