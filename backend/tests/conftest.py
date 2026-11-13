import importlib
import json
import os

import pytest
from httpx import ASGITransport, AsyncClient
from langgraph.checkpoint.memory import InMemorySaver
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.core.config import get_settings
from app.db.session import get_db
from app.main import app
from app.runtime.engine import RuntimeEngine
from baseline import cleanup, reset_baseline
from fakes import build_test_model


@pytest.fixture(autouse=True)
def isolate_skill_storage(monkeypatch, tmp_path):
    """测试期间强制 Skill 包落本地临时目录，避免误写真实 COS bucket。"""
    from app.services.skill_storage import reset_skill_storage

    settings = get_settings()
    monkeypatch.setattr(settings, "skill_storage", "local")
    monkeypatch.setattr(settings, "skill_dir", str(tmp_path / "skills"))
    reset_skill_storage()
    yield
    reset_skill_storage()


@pytest.fixture(autouse=True)
def deterministic_runtime_engine(monkeypatch):
    """集成测试用确定性对话模型驱动 LangGraph，避免依赖外网 LLM。

    这只替换引擎的模型工厂，图编排、工具执行、日志与持久化仍是真实实现。
    """
    engine = RuntimeEngine(
        checkpointer=InMemorySaver(),
        model_factory=lambda snapshot, tools, prompt: build_test_model(snapshot, tools, prompt, tool_rounds=1),
    )
    for module_name in ("app.api.v1.agents", "app.api.public.runtime", "app.api.public.agents"):
        monkeypatch.setattr(importlib.import_module(module_name), "get_runtime_engine", lambda: engine)


@pytest.fixture(autouse=True)
def upstream_probes(monkeypatch):
    """隔离外部网络：上游探测只做真实的 SSRF 校验，返回可控的探测结果。

    测试目标是服务端流程与落库行为，不依赖外部 LLM / MCP 服务是否在线。
    """
    import ipaddress
    from urllib.parse import urlparse

    from app.adapters import gateway
    from app.core.errors import ApiException

    def assert_no_private_ip(url: str) -> None:
        host = (urlparse(url).hostname or "").lower()
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            return  # 域名解析属于外部网络，测试不依赖 DNS
        if address.is_private or address.is_loopback or address.is_link_local or address.is_reserved:
            raise ApiException("validation_error", "该地址位于受限网络，已被 SSRF 防护拦截", 400)

    async def test_connection(self, draft):
        base_url = str(draft.get("baseUrl", "")).strip()
        if not gateway.is_http_url(base_url):
            raise ApiException("upstream_unavailable", "Base URL 不是有效的 HTTP(S) 地址", 502)
        assert_no_private_ip(base_url)
        if not str(draft.get("apiKey", "")).strip() or not str(draft.get("upstreamModelId", "")).strip():
            raise ApiException("upstream_auth_failed", "上游凭据缺失", 401)
        return {"latencyMs": 42, "httpStatus": 200, "errorCategory": None, "responseSummary": "探测通过"}

    async def discover_tools(self, draft):
        endpoint = str(draft.get("endpoint", "")).strip()
        if not endpoint:
            raise ApiException("validation_error", "服务地址不能为空", 400)
        if str(draft.get("transport")) != "stdio":
            assert_no_private_ip(endpoint)
        return [
            {
                "name": "search", "key": "search", "description": "检索",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string", "description": "检索关键词"},
                        "limit": {"type": "integer", "description": "返回条数", "default": 5},
                    },
                    "required": ["query"],
                },
            },
            {"name": "fetch", "key": "fetch", "description": "读取", "inputSchema": {"type": "object", "properties": {}}},
            {"name": "list", "key": "list", "description": "列举", "inputSchema": {"type": "object", "properties": {}}},
        ]

    async def call_tool(self, server, tool_key, arguments, timeout=30.0):
        return {
            "status": "success",
            "durationMs": 12,
            "output": f'{tool_key}:{json.dumps(arguments, ensure_ascii=False)}',
            "error": None,
        }

    monkeypatch.setattr(gateway.ModelGateway, "test_connection", test_connection)
    monkeypatch.setattr(gateway.McpGateway, "discover_tools", discover_tools)
    monkeypatch.setattr(gateway.McpGateway, "call_tool", call_tool)


@pytest.fixture
async def client() -> AsyncClient:
    # baseline.py 会按固定 ID 删除并写入演示基线数据，连接业务库会直接污染生产数据。
    # 因此集成测试强制使用独立数据库，未配置时直接终止而不是偷偷回退到 .env 的 DSN。
    test_url = os.environ.get("ALBEDO_TEST_DATABASE_URL", "").strip()
    if not test_url or test_url == get_settings().database_url:
        pytest.exit(
            "必须设置 ALBEDO_TEST_DATABASE_URL 指向独立测试库，且不得与 DATABASE_URL 相同",
            returncode=2,
        )
    db_engine = create_async_engine(test_url, poolclass=NullPool)
    maker = async_sessionmaker(db_engine, expire_on_commit=False, autoflush=False)

    async with maker() as session:
        await reset_baseline(session)

    async def override_db():
        async with maker() as session:
            try:
                yield session
            except Exception:
                await session.rollback()
                raise

    app.dependency_overrides[get_db] = override_db
    transport = ASGITransport(app=app, client=("testclient", 50000))
    try:
        async with AsyncClient(transport=transport, base_url="http://testserver") as instance:
            yield instance
    finally:
        async with maker() as session:
            await cleanup(session)
        app.dependency_overrides.clear()
        await db_engine.dispose()
