import pytest
from httpx import AsyncClient

from app.core.config import get_settings
from app.core.security import new_id

DEMO_KEY = get_settings().demo_access_key


async def login(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/login", json={"key": DEMO_KEY})
    assert response.status_code == 200, response.text


@pytest.mark.asyncio
async def test_agent_crud_and_optimistic_lock(client: AsyncClient) -> None:
    await login(client)
    created = await client.post("/api/v1/agents", json={
        "name": "集成测试智能体", "description": "由 pytest 创建",
        "defaultModelId": "mdl-gpt", "systemPrompt": "你是测试助手，当前时间 {{currentTime}}。",
    })
    assert created.status_code == 201, created.text
    agent = created.json()["entity"]
    assert agent["id"].startswith("agt-")
    assert agent["mounts"]["modelIds"] == ["mdl-gpt"]
    assert created.json()["version"] == 1

    updated = await client.patch(f"/api/v1/agents/{agent['id']}", json={
        "name": "集成测试智能体（已改）", "variables": ["topic"], "expectedVersion": 1,
    })
    assert updated.status_code == 200, updated.text
    assert updated.json()["version"] == 2
    assert updated.json()["entity"]["name"].endswith("（已改）")

    stale = await client.patch(f"/api/v1/agents/{agent['id']}", json={"name": "过期版本", "expectedVersion": 1})
    assert stale.status_code == 409
    assert stale.json()["error"]["code"] == "stale_version"

    blank_prompt = await client.patch(f"/api/v1/agents/{agent['id']}", json={"systemPrompt": "   "})
    assert blank_prompt.status_code == 400

    removed = await client.delete(f"/api/v1/agents/{agent['id']}")
    assert removed.status_code == 200
    listed = await client.get("/api/v1/agents")
    assert all(item["id"] != agent["id"] for item in listed.json())


@pytest.mark.asyncio
async def test_publish_check_protocol_and_credential(client: AsyncClient) -> None:
    await login(client)
    check = await client.post("/api/v1/agents/agt-research/publish-check")
    assert check.json()["passed"] is True, check.json()

    closed = await client.patch("/api/v1/agents/agt-research/protocols", json={"protocol": "api", "enabled": False})
    assert closed.json()["entity"]["apiEnabled"] is False
    opened = await client.patch("/api/v1/agents/agt-research/protocols", json={"protocol": "api", "enabled": True})
    assert opened.json()["entity"]["apiEnabled"] is True

    reset = await client.post("/api/v1/agents/agt-research/credential/reset")
    assert reset.json()["fullKey"].startswith("alb_agt_")
    assert reset.json()["credentialSuffix"] == reset.json()["fullKey"][-4:]
    bootstrap = await client.get("/api/v1/workspace/bootstrap")
    assert "accessKey" not in bootstrap.json()["settings"]

    history = await client.get("/api/v1/agents/agt-research/history")
    assert history.json()["total"] >= 1
    assert "standard_api" in history.json()["sources"]


@pytest.mark.asyncio
async def test_test_run_persists_messages_and_logs(client: AsyncClient) -> None:
    await login(client)
    before = await client.get("/api/v1/workspace/bootstrap")
    agent = next(item for item in before.json()["agents"] if item["id"] == "agt-research")
    count_before = agent["sessionCount"]

    result = await client.post("/api/v1/agents/agt-research/test-runs", json={
        "content": "请分析订单下降的原因", "testModelId": "mdl-gpt", "threadId": "thd_pytest_run",
    })
    assert result.status_code == 200, result.text
    body = result.json()
    assert body["runId"].startswith("run_")
    assert len(body["messages"]) >= 3
    assert body["messages"][0]["role"] == "user"
    assert body["messages"][-1]["role"] == "assistant"
    assert len(body["logs"]) >= 2
    assert body["sessionCount"] == count_before + 1

    after = await client.get("/api/v1/workspace/bootstrap")
    messages = after.json()["chatSessions"]["agt-research"]
    assert any(message["id"] == body["messages"][0]["id"] for message in messages)
    assert any(log["logId"] == body["logs"][0]["logId"] for log in after.json()["logs"])

    cleared = await client.delete("/api/v1/agents/agt-research/test-session")
    assert cleared.status_code == 200
    # 默认 thread 与调用方自定义 thread 都要被清理，否则会出现「消息已清空但仍记得」。
    assert cleared.json()["clearedThreads"] >= 2
    empty = await client.get("/api/v1/workspace/bootstrap")
    assert empty.json()["chatSessions"].get("agt-research", []) == []


@pytest.mark.asyncio
async def test_failed_run_writes_audit_log(client: AsyncClient, monkeypatch: pytest.MonkeyPatch) -> None:
    await login(client)
    from app.api.v1 import agents as agents_api
    from app.core.errors import ApiException
    from app.services.audit import make_log_entry

    class FailingEngine:
        async def run(self, request):
            error = ApiException("agent_run_failed", "模拟上游崩溃", 500)
            error.duration_ms = 15
            error.logs = [make_log_entry(
                "失败运行审计测试", "模拟上游崩溃",
                log_type="session", status="failed", source="test_session",
                runId=request.run_id, threadId=request.thread_id, agentId=request.agent_id,
                errorCode="agent_run_failed", errorMessage="模拟上游崩溃",
            )]
            raise error

    monkeypatch.setattr(agents_api, "get_runtime_engine", lambda: FailingEngine())
    response = await client.post("/api/v1/agents/agt-code/test-runs", json={"content": "触发失败", "testModelId": "mdl-gpt"})
    assert response.status_code == 500
    assert response.json()["error"]["code"] == "agent_run_failed"

    logs = await client.get("/api/v1/logs", params={"keyword": "失败运行审计测试", "range": "1y"})
    assert logs.status_code == 200
    items = logs.json()["items"]
    assert items, "失败运行必须写入审计日志"
    assert items[0]["status"] == "failed"
    assert items[0]["errorCode"] == "agent_run_failed"


@pytest.mark.asyncio
async def test_publication_versions_and_key_rotation(client: AsyncClient) -> None:
    await login(client)
    suffix = new_id("", 6)
    draft = {
        "version": f"v9.9.{suffix}", "callIdentifier": f"albedo-test-{suffix}",
        "apiKey": "alb_sk_live_test_1234567890", "headers": "{}",
        "temperature": 0.8, "maxOutputTokens": 2048, "qpm": 30,
    }
    created = await client.post("/api/v1/models/mdl-gpt/publications", json=draft)
    assert created.status_code == 201, created.text
    publication = created.json()["publication"]
    assert publication["id"].startswith("pub-")
    assert created.json()["fullKey"] == draft["apiKey"]

    duplicate = await client.post("/api/v1/models/mdl-gpt/publications", json=draft)
    assert duplicate.status_code == 409
    assert duplicate.json()["error"]["code"] == "duplicate_identifier"

    toggled = await client.patch(f"/api/v1/publications/{publication['id']}", json={"enabled": False})
    assert toggled.json()["entity"]["enabled"] is False

    rotated = await client.post(f"/api/v1/publications/{publication['id']}/key/rotate", json={"apiKey": "alb_sk_rotated_1234567890"})
    assert rotated.status_code == 200
    assert rotated.json()["fullKey"].endswith("7890")
    assert rotated.json()["entity"]["credentialSuffix"] == "7890"

    weak = await client.post(f"/api/v1/publications/{publication['id']}/key/rotate", json={"apiKey": "short"})
    assert weak.status_code == 400

    removed = await client.delete(f"/api/v1/publications/{publication['id']}")
    assert removed.status_code == 200
    remaining = await client.get("/api/v1/models/mdl-gpt/publications")
    assert all(item["id"] != publication["id"] for item in remaining.json())

    missing = await client.delete(f"/api/v1/publications/{publication['id']}")
    assert missing.status_code == 404
