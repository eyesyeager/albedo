import asyncio
from typing import Any

from langchain_openai import ChatOpenAI

from app.core.config import get_settings
from app.core.errors import ApiException

RETRYABLE_STATUS = {408, 409, 425, 429, 500, 502, 503, 504}


def upstream_status(exc: Exception) -> int | None:
    status = getattr(exc, "status_code", None)
    return status if isinstance(status, int) else None


def is_timeout_error(exc: Exception) -> bool:
    if isinstance(exc, (TimeoutError, asyncio.TimeoutError)):
        return True
    return "timeout" in type(exc).__name__.lower()


def is_retryable(exc: Exception) -> bool:
    status = upstream_status(exc)
    if status in RETRYABLE_STATUS:
        return True
    return is_timeout_error(exc) or isinstance(exc, (ConnectionError, OSError))


def map_upstream_error(exc: Exception) -> ApiException | None:
    status = upstream_status(exc)
    if status in (401, 403):
        return ApiException("upstream_auth_failed", "上游鉴权失败，请检查模型 API Key 或权限", 401)
    if status == 429:
        return ApiException("rate_limit_exceeded", "上游请求频率或配额超限，请稍后重试", 429)
    if status in (408, 504) or is_timeout_error(exc):
        return ApiException("upstream_timeout", "上游响应超时，请稍后重试", 504)
    if status is not None and status >= 500:
        return ApiException("upstream_unavailable", f"上游服务异常（HTTP {status}），请检查服务状态", 502)
    if isinstance(exc, (ConnectionError, OSError)):
        return ApiException("upstream_unavailable", "无法连接上游模型服务，请检查网络与 Base URL", 502)
    return None


def build_live_model(snapshot: dict, tool_names: list[str], rendered: str) -> ChatOpenAI:
    settings = get_settings()
    if not settings.llm_api_key or not settings.llm_model:
        raise ApiException(
            "upstream_auth_failed",
            "未配置真实模型凭据，请在 backend/.env 中设置 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL",
            401,
        )
    params: dict[str, Any] = snapshot.get("modelParams") or {}
    temperature = float(params.get("temperature", settings.llm_temperature))
    return ChatOpenAI(
        model=settings.llm_model,
        api_key=settings.llm_api_key,
        base_url=settings.llm_base_url or None,
        temperature=min(2.0, max(0.0, temperature)),
        timeout=settings.llm_timeout_seconds,
        max_retries=0,
    )


async def invoke_with_retry(model: Any, messages: list, max_retries: int = 0, base_delay: float = 0.6) -> Any:
    attempt = 0
    while True:
        try:
            return await model.ainvoke(messages)
        except Exception as exc:  # noqa: BLE001
            if attempt >= max_retries or not is_retryable(exc):
                raise
            await asyncio.sleep(base_delay * (2 ** attempt))
            attempt += 1
