import pytest
from httpx import AsyncClient

from app.core.config import get_settings

DEMO_KEY = get_settings().demo_access_key


async def login(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/login", json={"key": DEMO_KEY})
    assert response.status_code == 200, response.text


@pytest.mark.asyncio
async def test_logs_query_and_chain(client: AsyncClient) -> None:
    await login(client)
    result = await client.get("/api/v1/logs", params={"page": 1, "pageSize": 5, "range": "1y"})
    assert result.status_code == 200, result.text
    body = result.json()
    assert body["total"] >= 12
    assert len(body["items"]) <= 5
    assert "session" in body["typeCounts"]

    filtered = await client.get("/api/v1/logs", params={"type": "mcp", "range": "1y"})
    assert all(item["type"] == "mcp" for item in filtered.json()["items"])

    keyword = await client.get("/api/v1/logs", params={"keyword": "深度研究助手", "range": "1y"})
    assert keyword.json()["total"] >= 1

    # 深链依赖服务端按 runId 精确查询，不能只看前端快照。
    by_run = await client.get("/api/v1/logs", params={"runId": "run_8fk2m1", "range": "1y"})
    assert by_run.status_code == 200
    assert by_run.json()["total"] >= 3
    assert all(item["runId"] == "run_8fk2m1" for item in by_run.json()["items"])

    detail = await client.get("/api/v1/logs/log-1001")
    assert detail.status_code == 200
    assert len(detail.json()["chain"]) >= 3
    assert all(item["runId"] == "run_8fk2m1" for item in detail.json()["chain"])


@pytest.mark.asyncio
async def test_export_task_and_download(client: AsyncClient) -> None:
    await login(client)
    created = await client.post("/api/v1/logs/exports", json={
        "format": "csv", "filters": {}, "filtersLabel": "类型=全部 · 状态=失败", "logIds": ["log-1001", "log-1005"],
    })
    assert created.status_code == 202, created.text
    task_id = created.json()["id"]
    assert created.json()["status"] == "completed"

    status = await client.get(f"/api/v1/logs/exports/{task_id}")
    assert status.json()["task"]["status"] == "completed"
    assert status.json()["task"]["recordCount"] == 2
    assert status.json()["log"] is None
    audit_logs = await client.get("/api/v1/logs", params={"keyword": task_id, "range": "1y"})
    assert audit_logs.json()["total"] >= 1

    downloaded = await client.get(status.json()["downloadUrl"])
    assert downloaded.status_code == 200
    assert "log-1001" not in downloaded.text
    assert "时间" in downloaded.text


@pytest.mark.asyncio
async def test_stats_search_and_notifications(client: AsyncClient) -> None:
    await login(client)
    overview = await client.get("/api/v1/stats/overview")
    assert overview.json()["agentsTotal"] >= 4
    assert overview.json()["typeCounts"]["session"] >= 1
    assert "successRate" in overview.json()

    trends = await client.get("/api/v1/stats/trends", params={"days": 14})
    assert len(trends.json()) == 14
    assert all("label" in point for point in trends.json())

    search = await client.get("/api/v1/search", params={"q": "GPT"})
    assert any(item["kind"] == "model" for item in search.json())

    await client.post("/api/v1/notifications/read-all")
    listed = await client.get("/api/v1/notifications")
    assert all(item["read"] for item in listed.json())


@pytest.mark.asyncio
async def test_settings_retention_and_access_key(client: AsyncClient) -> None:
    await login(client)
    settings = await client.get("/api/v1/settings")
    assert "accessKey" not in settings.json()
    original = settings.json()["retentionDays"]

    updated = await client.patch("/api/v1/settings", json={"workspaceName": "集成测试工作区", "retentionDays": 30})
    assert updated.json()["settings"]["retentionDays"] == 30
    assert len(updated.json()["logs"]) == 2

    restored = await client.patch("/api/v1/settings", json={"retentionDays": original})
    assert restored.json()["settings"]["retentionDays"] == original

    temporary = "alb_temp_key_1234567890"
    try:
        changed = await client.post("/api/v1/settings/access-key", json={
            "current": DEMO_KEY, "next": temporary, "confirm": temporary,
        })
        assert changed.status_code == 200, changed.text
        assert changed.json()["settings"]["accessKeySuffix"] == temporary[-4:]
        invalid = await client.post("/api/v1/auth/login", json={"key": DEMO_KEY})
        assert invalid.status_code == 401
    finally:
        restored_key = await client.post("/api/v1/settings/access-key", json={
            "current": temporary, "next": DEMO_KEY, "confirm": DEMO_KEY,
        })
        assert restored_key.status_code == 200, restored_key.text


@pytest.mark.asyncio
async def test_system_configs_are_persisted(client: AsyncClient) -> None:
    await login(client)
    configs = await client.get("/api/v1/settings/configs")
    assert configs.status_code == 200, configs.text
    body = configs.json()
    assert {"platform", "options", "defaults", "limits", "runtime", "builtinTools"} <= set(body)

    original = body["limits"]["logPageSize"]
    patched = await client.patch("/api/v1/settings/configs", json={
        "limits": {**body["limits"], "logPageSize": original + 1},
    })
    assert patched.status_code == 200, patched.text
    refreshed = await client.get("/api/v1/settings/configs")
    assert refreshed.json()["limits"]["logPageSize"] == original + 1
    await client.patch("/api/v1/settings/configs", json={"limits": body["limits"]})

    removed = await client.post("/api/v1/settings/reset-demo")
    assert removed.status_code in (404, 405)
