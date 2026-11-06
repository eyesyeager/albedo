import asyncio
import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.config import get_settings  # noqa: E402


async def main() -> None:
    settings = get_settings()
    if not settings.llm_api_key:
        raise SystemExit("LLM_API_KEY 未配置，请检查 backend/.env")
    url = f"{settings.llm_base_url.rstrip('/')}/chat/completions"
    headers = {"Authorization": f"Bearer {settings.llm_api_key}", "Content-Type": "application/json"}
    messages = [{"role": "user", "content": "只回复：pong"}]

    async with httpx.AsyncClient(timeout=settings.llm_timeout_seconds) as client:
        plain = await client.post(url, headers=headers, json={
            "model": settings.llm_model, "messages": messages, "max_tokens": 32, "temperature": 0,
        })
        data = plain.json()
        content = (data.get("choices") or [{}])[0].get("message", {}).get("content", "")
        print("chat_status:", plain.status_code)
        print("chat_content:", str(content)[:80])
        print("chat_usage:", data.get("usage"))

        tools = [{
            "type": "function",
            "function": {
                "name": "current_time",
                "description": "返回当前时间",
                "parameters": {"type": "object", "properties": {"timezone": {"type": "string"}}, "required": []},
            },
        }]
        tool_response = await client.post(url, headers=headers, json={
            "model": settings.llm_model,
            "messages": [{"role": "user", "content": "现在几点？请调用工具获取时间。"}],
            "tools": tools,
            "tool_choice": "auto",
            "max_tokens": 128,
            "temperature": 0,
        })
        tool_data = tool_response.json()
        message = (tool_data.get("choices") or [{}])[0].get("message", {})
        print("tool_status:", tool_response.status_code)
        print("tool_calls:", [call.get("function", {}).get("name") for call in (message.get("tool_calls") or [])])
        if tool_response.status_code >= 400:
            print("tool_error:", str(tool_data)[:200])


if __name__ == "__main__":
    asyncio.run(main())
