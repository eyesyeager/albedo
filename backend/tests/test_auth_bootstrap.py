import pytest
from httpx import AsyncClient

from app.core.config import get_settings
from app.core.redis_client import try_delete
from app.schemas.domain import WorkspaceData

DEMO_KEY = get_settings().demo_access_key


@pytest.mark.asyncio
async def test_bootstrap_requires_session(client: AsyncClient) -> None:
    response = await client.get("/api/v1/workspace/bootstrap")
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "invalid_api_key"
    assert response.json()["error"]["requestId"].startswith("req_")


@pytest.mark.asyncio
async def test_session_is_false_without_cookie(client: AsyncClient) -> None:
    response = await client.get("/api/v1/auth/session")
    assert response.status_code == 200
    assert response.json()["authenticated"] is False


@pytest.mark.asyncio
async def test_login_and_bootstrap(client: AsyncClient) -> None:
    await try_delete("albedo:auth:fail:testclient")
    response = await client.post("/api/v1/auth/login", json={"key": DEMO_KEY})
    assert response.status_code == 200, response.text
    assert response.json()["authenticated"] is True
    assert get_settings().session_cookie_name in response.cookies

    session = await client.get("/api/v1/auth/session")
    assert session.json()["authenticated"] is True

    bootstrap = await client.get("/api/v1/workspace/bootstrap")
    assert bootstrap.status_code == 200, bootstrap.text
    data = WorkspaceData.model_validate(bootstrap.json())
    assert len(data.agents) >= 4
    assert len(data.models) >= 4
    assert len(data.publications) >= 3
    assert len(data.mcps) >= 3
    assert data.settings.accessKeySuffix == "K9P4"
    assert "accessKey" not in bootstrap.json()["settings"]
    assert any(agent.id == "agt-research" for agent in data.agents)
    assert data.logs and len(data.logs) <= 50
    assert (await client.get("/api/v1/logs/log-1001")).status_code == 200
    assert len(data.trends.d30) == 30

    logout = await client.post("/api/v1/auth/logout")
    assert logout.status_code == 200
    assert (await client.get("/api/v1/auth/session")).json()["authenticated"] is False


@pytest.mark.asyncio
async def test_invalid_key_and_rate_limit(client: AsyncClient) -> None:
    await try_delete("albedo:auth:fail:testclient")
    for _ in range(5):
        failed = await client.post("/api/v1/auth/login", json={"key": "wrong-key-123456789"})
        assert failed.status_code == 401
        assert failed.json()["error"]["code"] == "invalid_api_key"
    limited = await client.post("/api/v1/auth/login", json={"key": "wrong-key-123456789"})
    assert limited.status_code == 429
    assert limited.json()["error"]["code"] == "rate_limit_exceeded"
    await try_delete("albedo:auth:fail:testclient")

    success = await client.post("/api/v1/auth/login", json={"key": DEMO_KEY})
    assert success.status_code == 200
