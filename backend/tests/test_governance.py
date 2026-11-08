import pytest
from httpx import AsyncClient
from sqlalchemy import delete

from app.core.config import get_settings
from app.core.security import now_iso
from app.db import models as m
from app.db.session import SessionLocal
from app.schemas.domain import log_type_values, normalize_log_type
from app.services.audit import make_log_entry

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


def test_skill_l3_kinds_are_normalized_at_write_time() -> None:
    """运行时工具 kind 不得击穿 LogType 枚举（P0 回归）。"""
    assert normalize_log_type("skill_resource") == "skill"
    assert normalize_log_type("skill_script") == "skill"
    assert normalize_log_type("mcp") == "mcp"
    assert normalize_log_type("unknown-kind") == "tool"
    assert normalize_log_type(None, default="system") == "system"
    assert set(log_type_values("skill")) == {"skill", "skill_resource", "skill_script"}
    assert log_type_values("mcp") == ("mcp",)
    assert make_log_entry("读取 Skill 资源", "L3", log_type="skill_resource")["type"] == "skill"
    assert make_log_entry("执行 Skill 脚本", "L3", log_type="skill_script")["type"] == "skill"


@pytest.mark.asyncio
async def test_legacy_skill_l3_log_keeps_all_read_paths_healthy(client: AsyncClient) -> None:
    """历史越界日志不得让日志页、执行链路、快照、概览返回 500（P0 回归）。"""
    await login(client)
    log_id = "log-regression-skill-l3"
    run_id = "run-regression-skill-l3"

    async def remove() -> None:
        async with SessionLocal() as session:
            await session.execute(delete(m.LogEntry).where(m.LogEntry.log_id == log_id))
            await session.commit()

    await remove()
    async with SessionLocal() as session:
        session.add(m.LogEntry(
            log_id=log_id,
            timestamp=now_iso(),
            type="skill_resource",
            status="success",
            source="test_session",
            name="回归 Skill L3 日志",
            summary="模拟修复前写入的越界类型",
            run_id=run_id,
            thread_id="thd-regression-skill-l3",
            agent_id="agt-research",
        ))
        await session.commit()
    try:
        page = await client.get("/api/v1/logs", params={"page": 1, "pageSize": 20, "range": "1y"})
        assert page.status_code == 200, page.text
        assert page.json()["typeCounts"].get("skill", 0) >= 1
        assert "skill_resource" not in page.json()["typeCounts"]

        filtered = await client.get("/api/v1/logs", params={
            "type": "skill", "runId": run_id, "range": "1y",
        })
        assert filtered.status_code == 200, filtered.text
        assert [item["logId"] for item in filtered.json()["items"]] == [log_id]
        assert filtered.json()["items"][0]["type"] == "skill"

        by_run = await client.get("/api/v1/logs", params={"runId": run_id, "range": "1y"})
        assert by_run.status_code == 200, by_run.text
        assert by_run.json()["total"] == 1

        detail = await client.get(f"/api/v1/logs/{log_id}")
        assert detail.status_code == 200, detail.text
        assert detail.json()["type"] == "skill"
        assert detail.json()["chain"][0]["type"] == "skill"

        bootstrap = await client.get("/api/v1/workspace/bootstrap")
        assert bootstrap.status_code == 200, bootstrap.text
        assert all(item["type"] in ("session", "mcp", "skill", "tool", "model", "system")
                   for item in bootstrap.json()["logs"])

        overview = await client.get("/api/v1/stats/overview")
        assert overview.status_code == 200, overview.text
        assert "skill_resource" not in overview.json()["typeCounts"]
    finally:
        await remove()
