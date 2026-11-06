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

    not_allowed = await client.post(
        "/v1/chat/completions",
        headers=auth(token),
        json={"model": "another-model", "messages": [{"role": "user", "content": "hi"}]},
    )
    assert not_allowed.status_code == 403
    assert not_allowed.json()["error"]["code"] == "model_not_allowed"

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
