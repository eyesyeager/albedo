import io
import zipfile

import pytest
from httpx import AsyncClient

from app.core.config import get_settings

DEMO_KEY = get_settings().demo_access_key


def skill_zip() -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("manifest.json", '{"name":"demo-skill","version":"1.0.0","entry":"main.py"}')
        archive.writestr("main.py", "print('demo')\n")
    return buffer.getvalue()


async def login(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/login", json={"key": DEMO_KEY})
    assert response.status_code == 200, response.text


@pytest.mark.asyncio
async def test_model_crud_and_real_connection(client: AsyncClient) -> None:
    await login(client)
    draft = {
        "name": "测试模型", "upstreamModelId": "probe-chat", "type": "chat", "vendor": "Probe",
        "baseUrl": "https://api.example.com/v1", "apiKey": "sk-test-1234567890", "headers": '{"X-Env":"test"}',
        "timeoutSeconds": 30, "note": "集成测试", "enabled": True, "tested": False,
    }
    tested = await client.post("/api/v1/models/test", json=draft)
    assert tested.status_code == 200, tested.text
    assert 0 < tested.json()["latencyMs"] < 5000  # 上游探测返回的实测耗时

    created = await client.post("/api/v1/models", json={**draft, "tested": True})
    assert created.status_code == 201, created.text
    body = created.json()
    entity = body["entity"]
    assert entity["id"].startswith("mdl-")
    assert entity["connection"] == "normal"
    assert entity["headers"] == '{"X-Env":"test"}'
    assert body["log"]["type"] == "system"

    toggled = await client.patch(f"/api/v1/models/{entity['id']}/enabled", json={"enabled": False})
    assert toggled.json()["entity"]["enabled"] is False
    listed = await client.get("/api/v1/models")
    assert any(item["id"] == entity["id"] and item["enabled"] is False for item in listed.json())

    stored = await client.get(f"/api/v1/models/{entity['id']}")
    assert stored.json()["credentialSuffix"] == "7890"

    retested = await client.post(f"/api/v1/models/{entity['id']}/test")
    assert retested.status_code == 200, retested.text
    assert 0 < retested.json()["latencyMs"] < 5000
    assert retested.json()["entity"]["connection"] == "normal"

    deleted = await client.delete(f"/api/v1/models/{entity['id']}")
    assert deleted.status_code == 200, deleted.text
    gone = await client.get(f"/api/v1/models/{entity['id']}")
    assert gone.status_code == 404


@pytest.mark.asyncio
async def test_model_test_rejects_ssrf(client: AsyncClient) -> None:
    await login(client)
    response = await client.post("/api/v1/models/test", json={
        "baseUrl": "http://169.254.169.254/latest/meta-data",
        "apiKey": "sk-test-1234567890",
        "upstreamModelId": "probe-chat",
    })
    assert response.status_code == 400
    assert "SSRF" in response.json()["error"]["message"]


@pytest.mark.asyncio
async def test_mcp_crud_and_tool_toggle(client: AsyncClient) -> None:
    await login(client)
    draft = {
        "name": "测试 MCP", "description": "集成测试", "transport": "streamable_http",
        "endpoint": "https://mcp.example.com/mcp", "headers": "{}", "timeoutSeconds": 60, "tested": True,
    }
    discovered = await client.post("/api/v1/mcps/test", json=draft)
    assert discovered.json()["toolCount"] == 3

    created = await client.post("/api/v1/mcps", json=draft)
    assert created.status_code == 201, created.text
    entity = created.json()["entity"]
    assert len(entity["tools"]) == 3
    tool_id = entity["tools"][0]["id"]

    toggled = await client.patch(f"/api/v1/mcps/{entity['id']}/tools/{tool_id}", json={"enabled": False})
    assert toggled.json()["entity"]["tools"][0]["enabled"] is False

    usage = await client.patch(f"/api/v1/mcps/{entity['id']}/usage", json={"qpm": 120, "maxConcurrency": 8})
    assert usage.json()["entity"]["qpm"] == 120

    calls = await client.get(f"/api/v1/mcps/{entity['id']}/calls")
    assert calls.json()["total"] == 0


@pytest.mark.asyncio
async def test_skill_upload_and_version(client: AsyncClient) -> None:
    await login(client)
    payload = skill_zip()
    files = {"file": ("demo-skill.zip", payload, "application/zip")}
    data = {"name": "测试 Skill", "instructions": "功能：测试。使用步骤：挂载后调用。", "fileName": "demo-skill.zip", "sizeBytes": len(payload)}
    created = await client.post("/api/v1/skills", data=data, files=files)
    assert created.status_code == 201, created.text
    entity = created.json()["entity"]
    assert entity["version"] == "v1.0.0"
    assert len(entity["versions"]) == 1

    versioned = await client.post(f"/api/v1/skills/{entity['id']}/versions", data=data, files=files)
    assert versioned.status_code == 201, versioned.text
    newer = versioned.json()["entity"]
    assert newer["version"] == "v1.1.0"
    assert len(newer["versions"]) == 2

    detail = await client.get(f"/api/v1/skills/{entity['id']}")
    assert detail.json()["version"] == "v1.1.0"


@pytest.mark.asyncio
async def test_tools_toggle_and_impact(client: AsyncClient) -> None:
    await login(client)
    tools = await client.get("/api/v1/tools")
    assert len(tools.json()) >= 6
    target = tools.json()[0]
    toggled = await client.patch(f"/api/v1/tools/{target['id']}/enabled", json={"enabled": not target["enabled"]})
    assert toggled.json()["entity"]["enabled"] is not target["enabled"]
    await client.patch(f"/api/v1/tools/{target['id']}/enabled", json={"enabled": target["enabled"]})

    impact = await client.get("/api/v1/resources/model/mdl-gpt/impact")
    assert impact.status_code == 200
    assert any(agent["id"] == "agt-research" for agent in impact.json()["agents"])
