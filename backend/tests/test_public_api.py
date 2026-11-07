import json
from uuid import uuid4

import pytest
from httpx import AsyncClient

from app.core.config import get_settings

DEMO_KEY = get_settings().demo_access_key


async def admin_login(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/login", json={"key": DEMO_KEY})
    assert response.status_code == 200


async def reset_agent_token(client: AsyncClient, agent_id: str = "agt-research") -> str:
    response = await client.post(f"/api/v1/agents/{agent_id}/credential/reset")
    assert response.status_code == 200, response.text
    return response.json()["fullKey"]


def auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def sse_events(body: str) -> list[str]:
    return [line[7:] for line in body.splitlines() if line.startswith("event: ")]


def sse_data(body: str) -> list[dict]:
    return [json.loads(line[6:]) for line in body.splitlines() if line.startswith("data: ")]


@pytest.mark.asyncio
async def test_public_requires_bearer(client: AsyncClient) -> None:
    response = await client.post("/v1/agents/agt-research/chat", json={"messages": [{"role": "user", "content": "hi"}]})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "invalid_api_key"


@pytest.mark.asyncio
async def test_public_agent_chat_and_access_control(client: AsyncClient) -> None:
    await admin_login(client)
    token = await reset_agent_token(client)
    variables = {"researchDepth": "标准", "outputLanguage": "中文"}

    response = await client.post(
        "/v1/agents/agt-research/chat",
        headers=auth(token),
        json={"messages": [{"role": "user", "content": "请用一句话说明测试目的"}], "variables": variables, "threadId": "thd_public_test"},
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["object"] == "agent.chat.completion"
    assert body["agentId"] == "agt-research"
    assert body["status"] == "completed"
    assert body["message"]["role"] == "assistant"
    assert body["usage"]["totalTokens"] >= 0
    assert body["metadata"]["durationMs"] >= 0

    forbidden = await client.post(
        "/v1/agents/agt-code/chat",
        headers=auth(token),
        json={"messages": [{"role": "user", "content": "hi"}]},
    )
    assert forbidden.status_code == 403
    assert forbidden.json()["error"]["code"] == "agent_access_denied"

    invalid = await client.post(
        "/v1/agents/agt-research/chat",
        headers=auth("alb_agt_invalid_token_0000"),
        json={"messages": [{"role": "user", "content": "hi"}]},
    )
    assert invalid.status_code == 401

    missing_variable = await client.post(
        "/v1/agents/agt-research/chat",
        headers=auth(token),
        json={"messages": [{"role": "user", "content": "缺少变量"}]},
    )
    assert missing_variable.status_code == 422
    assert missing_variable.json()["error"]["code"] == "missing_variable"


@pytest.mark.asyncio
async def test_public_agent_chat_stream(client: AsyncClient) -> None:
    await admin_login(client)
    token = await reset_agent_token(client)
    async with client.stream(
        "POST",
        "/v1/agents/agt-research/chat",
        headers=auth(token),
        json={
            "messages": [{"role": "user", "content": "流式输出测试"}],
            "variables": {"researchDepth": "标准", "outputLanguage": "中文"},
            "threadId": "thd_public_stream",
            "stream": True,
        },
    ) as response:
        assert response.status_code == 200, await response.aread()
        assert response.headers["content-type"].startswith("text/event-stream")
        body = "".join([chunk async for chunk in response.aiter_text()])
    events = sse_events(body)
    assert events[0] == "message.start"
    assert "message.delta" in events
    assert events[-1] == "message.end"


@pytest.mark.asyncio
async def test_agui_event_order_and_resume(client: AsyncClient) -> None:
    await admin_login(client)
    token = await reset_agent_token(client)
    payload = {
        "threadId": "thd_agui_pytest",
        "runId": f"run_agui_{uuid4().hex[:8]}",
        "messages": [{"id": "msg-1", "role": "user", "content": "AGUI 测试"}],
        "context": {"variables": {"researchDepth": "标准", "outputLanguage": "中文"}},
    }
    async with client.stream("POST", "/api/agui/v1/agents/agt-research/run", headers=auth(token), json=payload) as response:
        assert response.status_code == 200, await response.aread()
        assert response.headers["x-agui-version"] == "1"
        body = "".join([chunk async for chunk in response.aiter_text()])

    events = sse_events(body)
    assert events[0] == "RUN_STARTED"
    assert events[-1] == "RUN_FINISHED"
    assert "TEXT_MESSAGE_START" in events
    assert "TEXT_MESSAGE_END" in events
    deltas = [item for item in sse_data(body) if item.get("type") == "TEXT_MESSAGE_CONTENT"]
    assert len(deltas) >= 2, "AGUI 必须逐 token 增量输出"
    assert "已收到你的问题" in "".join(str(item.get("delta") or "") for item in deltas)
    sequences = [item["sequence"] for item in sse_data(body)]
    assert sequences == list(range(1, len(sequences) + 1))

    async with client.stream(
        "POST", "/api/agui/v1/agents/agt-research/run",
        headers={**auth(token), "Last-Event-ID": "2"}, json=payload,
    ) as resumed:
        resumed_body = "".join([chunk async for chunk in resumed.aiter_text()])
    first_sequence = sse_data(resumed_body)[0]["sequence"]
    assert first_sequence == 3

    code_token = await reset_agent_token(client, "agt-code")
    disabled = await client.post(
        "/api/agui/v1/agents/agt-code/run",
        headers=auth(code_token),
        json={"threadId": "thd_agui_code", "runId": "run_agui_code", "messages": [{"id": "m", "role": "user", "content": "hi"}]},
    )
    assert disabled.status_code == 409
    assert disabled.json()["error"]["code"] == "agent_not_published"


@pytest.mark.asyncio
async def test_publication_key_models_and_errors(client: AsyncClient) -> None:
    await admin_login(client)
    rotated = await client.post(
        "/api/v1/publications/pub-gpt-stable/key/rotate",
        json={"apiKey": "alb_sk_live_pytest_1234567890"},
    )
    assert rotated.status_code == 200
    token = rotated.json()["fullKey"]

    models = await client.get("/v1/models", headers=auth(token))
    assert models.status_code == 200, models.text
    assert models.json()["data"][0]["id"] == "albedo-gpt-4.1"

    invalid = await client.get("/v1/models", headers=auth("alb_sk_invalid_key_0000"))
    assert invalid.status_code == 401

    # 标识不存在 → 404；存在但 Key 无权 → 403，两者语义必须区分。
    not_found = await client.post(
        "/v1/chat/completions",
        headers=auth(token),
        json={"model": "another-model", "messages": [{"role": "user", "content": "hi"}]},
    )
    assert not_found.status_code == 404
    assert not_found.json()["error"]["code"] == "model_not_found"

    not_allowed = await client.post(
        "/v1/chat/completions",
        headers=auth(token),
        json={"model": "albedo-gpt-4.1-creative", "messages": [{"role": "user", "content": "hi"}]},
    )
    assert not_allowed.status_code == 403
    assert not_allowed.json()["error"]["code"] == "model_not_allowed"

    # 上游凭证缺失属于网关侧不可用，返回 502 而不是「版本停用」409。
    no_credential = await client.post(
        "/v1/chat/completions",
        headers=auth(token),
        json={"model": "albedo-gpt-4.1", "messages": [{"role": "user", "content": "hi"}]},
    )
    assert no_credential.status_code == 502
    assert no_credential.json()["error"]["code"] == "upstream_not_configured"

    wrong_type = await client.post(
        "/v1/embeddings",
        headers=auth(token),
        json={"model": "albedo-gpt-4.1", "input": "hello"},
    )
    assert wrong_type.status_code == 400


@pytest.mark.asyncio
async def test_public_agent_not_published(client: AsyncClient) -> None:
    await admin_login(client)
    token = await reset_agent_token(client, "agt-data")
    response = await client.post(
        "/v1/agents/agt-data/chat",
        headers=auth(token),
        json={"messages": [{"role": "user", "content": "hi"}]},
    )
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "agent_not_published"


def test_live_model_timeout_covers_max_run_seconds() -> None:
    """推理模型首 token 很慢时，上游读超时不得短于整轮 maxRunSeconds。"""
    from app.runtime.live_model import build_live_model

    model = build_live_model({"advanced": {"maxRunSeconds": 300}, "modelParams": {}}, [], "")
    assert float(model.request_timeout) >= 300


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "terminal,answer",
    [
        ("run_finished", "这是最终答案"),
        ("run_error", "本次运行超过 300 秒上限，已终止。"),
    ],
)
async def test_agui_stream_returns_text_when_only_reasoning_was_streamed(monkeypatch, terminal, answer) -> None:
    """推理模型只输出 reasoning_content（或正文出现前超时）时 AGUI 仍须给出文本。"""
    from types import SimpleNamespace

    from app.api.public import agents as public_agents

    class ReasoningOnlyEngine:
        async def run_stream(self, request):
            yield {"type": "run_started", "runId": request.run_id, "threadId": request.thread_id}
            yield {"type": "thinking_delta", "delta": "先梳理问题。"}
            yield {"type": "thinking_delta", "delta": "再检查论证。"}
            yield {
                "type": terminal,
                "code": "agent_run_timeout" if terminal == "run_error" else "",
                "message": "运行超过最大时长限制，已终止" if terminal == "run_error" else "",
                "result": SimpleNamespace(
                    messages=[{"role": "user", "content": "hi"}, {"role": "assistant", "content": answer}],
                    logs=[], usage={}, duration_ms=10,
                    finish_reason="timeout" if terminal == "run_error" else "stop",
                    run_id=request.run_id, thread_id=request.thread_id, tool_call_count=0,
                ),
            }

    async def no_persist(*args, **kwargs):
        return None

    monkeypatch.setattr(public_agents, "get_runtime_engine", lambda: ReasoningOnlyEngine())
    monkeypatch.setattr(public_agents, "persist_result", no_persist)
    monkeypatch.setattr(public_agents, "persist_failure_logs", no_persist)
    monkeypatch.setattr(public_agents, "append_events", no_persist)

    request = SimpleNamespace(run_id="run_reasoning_only", thread_id="thd_reasoning_only")
    body = "".join([frame async for frame in public_agents.agui_stream(None, None, request, {}, request.thread_id)])

    events = sse_events(body)
    data = sse_data(body)
    assert events[0] == "RUN_STARTED"
    assert events[-1] == ("RUN_FINISHED" if terminal == "run_finished" else "RUN_ERROR")
    assert events.count("THINKING_TEXT_MESSAGE_START") == 1
    assert events.count("THINKING_TEXT_MESSAGE_CONTENT") == 2
    assert events.count("THINKING_TEXT_MESSAGE_END") == 1
    assert "TEXT_MESSAGE_START" in events and "TEXT_MESSAGE_END" in events
    text = "".join(str(item.get("delta") or "") for item in data if item["type"] == "TEXT_MESSAGE_CONTENT")
    assert text == answer
    assert [item["sequence"] for item in data] == list(range(1, len(data) + 1))


@pytest.mark.asyncio
async def test_public_chat_stream_returns_text_when_only_reasoning_was_streamed(monkeypatch) -> None:
    """标准 API 流同样不能因为只有 reasoning 增量而返回空消息。"""
    from types import SimpleNamespace

    from app.api.public import agents as public_agents

    class ReasoningOnlyEngine:
        async def run_stream(self, request):
            yield {"type": "run_started", "runId": request.run_id, "threadId": request.thread_id}
            yield {"type": "thinking_delta", "delta": "先梳理问题。"}
            yield {
                "type": "run_finished",
                "result": SimpleNamespace(
                    messages=[{"role": "assistant", "content": "最终回复"}],
                    logs=[], usage={}, duration_ms=10, finish_reason="stop",
                    run_id=request.run_id, thread_id=request.thread_id, tool_call_count=0,
                ),
            }

    async def no_persist(*args, **kwargs):
        return None

    monkeypatch.setattr(public_agents, "get_runtime_engine", lambda: ReasoningOnlyEngine())
    monkeypatch.setattr(public_agents, "persist_result", no_persist)

    request = SimpleNamespace(run_id="run_chat_reasoning", thread_id="thd_chat_reasoning")
    body = "".join([frame async for frame in public_agents.chat_stream(None, None, request, {})])
    events = sse_events(body)
    data = sse_data(body)
    assert "message.thinking" in events
    assert events[-1] == "message.end"
    assert any(item.get("delta") == "最终回复" for item in data if item.get("type") == "message.delta")
