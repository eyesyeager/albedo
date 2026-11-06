import json

import pytest
from httpx import AsyncClient
from langgraph.checkpoint.memory import InMemorySaver

from app.core.config import get_settings
from app.core.errors import ApiException
from app.runtime import RuntimeEngine, RuntimeRequest
from app.runtime.prompts import render_system_prompt
from fakes import build_test_model

DEMO_KEY = get_settings().demo_access_key


def make_snapshot(**advanced: object) -> dict:
    snapshot = {
        "id": "agt-runtime",
        "name": "运行时测试助手",
        "systemPrompt": "你是运行时测试助手。当前时间：{{currentTime}}。",
        "variables": [],
        "modelParams": {"temperature": 0.7, "topP": 0.9, "maxOutputTokens": 4096},
        "advanced": {
            "keepContext": True,
            "autoRetry": True,
            "outputThinkingSummary": False,
            "maxToolCalls": 10,
            "maxRunSeconds": 120,
        },
        "mounts": {"mcpIds": [], "skillIds": [], "toolIds": [], "modelIds": []},
        "resolvedMounts": {"mcp": [], "skill": [], "tool": [], "model": []},
        "model": {"id": "mdl-gpt", "name": "GPT-4.1"},
    }
    snapshot["advanced"].update(advanced)
    return snapshot


def model_factory(tool_rounds: int = 1, delay: float = 0.0):
    def build(snapshot: dict, tool_names: list[str], rendered: str):
        return build_test_model(
            snapshot, tool_names, rendered,
            tool_rounds=tool_rounds, delay_seconds=delay,
        )

    return build


def request(content: str, snapshot: dict, *, run_id: str = "run_test01", strict: bool = False, variables: dict | None = None) -> RuntimeRequest:
    return RuntimeRequest(
        agent_id="agt-runtime",
        thread_id="thd_runtime",
        run_id=run_id,
        content=content,
        snapshot=snapshot,
        variables=variables or {},
        source="test_session",
        strict_variables=strict,
    )


def test_variable_injection_and_missing_variable() -> None:
    rendered, missing = render_system_prompt(
        {"systemPrompt": "研究深度：{{researchDepth}}，时间：{{currentTime}}", "variables": ["researchDepth"]},
        {"researchDepth": "深度"},
        current_time="2026-11-05T00:00:00Z",
    )
    assert "研究深度：深度" in rendered
    assert "2026-11-05T00:00:00Z" in rendered
    assert "{{" not in rendered
    assert missing == []

    with pytest.raises(ApiException) as error:
        render_system_prompt(
            {"systemPrompt": "研究深度：{{researchDepth}}", "variables": ["researchDepth"]},
            {},
            strict=True,
        )
    assert error.value.code == "missing_variable"
    assert error.value.status_code == 422


@pytest.mark.asyncio
async def test_engine_stop_without_tools() -> None:
    engine = RuntimeEngine(checkpointer=InMemorySaver(), model_factory=model_factory(tool_rounds=1))
    snapshot = make_snapshot()
    original = json.dumps(snapshot, sort_keys=True, ensure_ascii=False)

    result = await engine.run(request("你好，介绍一下自己", snapshot))

    assert result.finish_reason == "stop"
    assert result.tool_call_count == 0
    assert [message["role"] for message in result.messages] == ["user", "assistant"]
    assert result.messages[0]["content"] == "你好，介绍一下自己"
    assert "运行时测试助手" in result.messages[1]["content"]
    assert result.logs[-1]["type"] == "session"
    assert result.logs[-1]["status"] == "success"
    assert "finishReason: stop" in result.logs[-1]["outputSummary"]
    assert result.usage["totalTokens"] >= 0
    assert json.dumps(snapshot, sort_keys=True, ensure_ascii=False) == original


@pytest.mark.asyncio
async def test_engine_tool_limit_terminates() -> None:
    engine = RuntimeEngine(checkpointer=InMemorySaver(), model_factory=model_factory(tool_rounds=5))
    snapshot = make_snapshot(maxToolCalls=1)
    snapshot["resolvedMounts"]["tool"] = [
        {"id": "tool-web", "name": "联网搜索", "key": "web_search", "description": "检索网页", "enabled": True},
        {"id": "tool-file", "name": "文件解析", "key": "file_parser", "description": "解析文件", "enabled": True},
    ]

    result = await engine.run(request("请检索资料并总结", snapshot, run_id="run_limit1"))

    assert result.finish_reason == "tool_limit"
    assert result.tool_call_count == 1
    roles = [message["role"] for message in result.messages]
    assert roles[0] == "user"
    assert "tool" in roles
    assert roles[-1] == "assistant"
    tool_logs = [log for log in result.logs if log["type"] == "tool"]
    assert len(tool_logs) == 1
    assert "tool_limit" in result.logs[-1]["outputSummary"]


@pytest.mark.asyncio
async def test_engine_timeout_terminates() -> None:
    engine = RuntimeEngine(checkpointer=InMemorySaver(), model_factory=model_factory(delay=0.6))
    snapshot = make_snapshot(maxRunSeconds=0.15)

    result = await engine.run(request("这是一个超时测试", snapshot, run_id="run_slow1"))

    assert result.finish_reason == "timeout"
    assert result.logs[-1]["type"] == "session"
    assert result.logs[-1]["status"] == "failed"
    assert "运行超过 maxRunSeconds" in result.logs[-1]["outputSummary"]


@pytest.mark.asyncio
async def test_engine_keep_context_reuses_thread() -> None:
    engine = RuntimeEngine(checkpointer=InMemorySaver(), model_factory=model_factory(tool_rounds=1))
    snapshot = make_snapshot()

    first = await engine.run(request("第一轮问题", snapshot, run_id="run_ctx1"))
    second = await engine.run(request("第二轮问题", snapshot, run_id="run_ctx2"))

    assert [message["role"] for message in first.messages] == ["user", "assistant"]
    assert [message["role"] for message in second.messages] == ["user", "assistant"]

    no_context = make_snapshot(keepContext=False)
    third = await engine.run(request("全新会话", no_context, run_id="run_ctx3"))
    assert third.thread_id.endswith("run_ctx3")


@pytest.mark.asyncio
async def test_api_test_run_uses_engine_and_persists(client: AsyncClient) -> None:
    login = await client.post("/api/v1/auth/login", json={"key": DEMO_KEY})
    assert login.status_code == 200

    response = await client.post("/api/v1/agents/agt-research/test-runs", json={
        "content": "请分析订单下降的原因",
        "testModelId": "mdl-gpt",
        "threadId": "thd_runtime_api",
    })
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["runId"].startswith("run_")
    assert body["threadId"] == "thd_runtime_api"
    assert body["messages"][0]["role"] == "user"
    assert any(message["role"] == "tool" for message in body["messages"])
    assert body["messages"][-1]["role"] == "assistant"
    assert body["logs"][-1]["type"] == "session"
    assert body["logs"][-1]["runId"] == body["runId"]

    logs = await client.get("/api/v1/logs", params={"keyword": body["runId"], "range": "1y"})
    assert logs.json()["total"] >= 3

    detail = await client.get("/api/v1/workspace/bootstrap")
    agent = next(item for item in detail.json()["agents"] if item["id"] == "agt-research")
    assert agent["sessionCount"] >= 1285


@pytest.mark.asyncio
async def test_thinking_summary_message() -> None:
    engine = RuntimeEngine(checkpointer=InMemorySaver(), model_factory=model_factory(tool_rounds=1))
    snapshot = make_snapshot(outputThinkingSummary=True)

    result = await engine.run(request("测试思考摘要", snapshot, run_id="run_think1"))

    summaries = [message for message in result.messages if message["content"].startswith("思考摘要")]
    assert len(summaries) == 1
    assert "不输出模型私有推理链" in summaries[0]["content"]


class _FlakySaver(InMemorySaver):
    """首次读取状态时抛出 Redis 超时，用于验证 checkpointer 降级。"""

    def __init__(self) -> None:
        super().__init__()
        self.reads = 0

    async def aget_tuple(self, config):  # type: ignore[override]
        self.reads += 1
        if self.reads == 1:
            raise TimeoutError("Timeout reading from 127.0.0.1:6379")
        return await super().aget_tuple(config)


@pytest.mark.asyncio
async def test_engine_degrades_when_checkpointer_fails() -> None:
    saver = _FlakySaver()
    engine = RuntimeEngine(checkpointer=saver, model_factory=model_factory(tool_rounds=1))

    result = await engine.run(request("checkpointer 降级测试", make_snapshot(), run_id="run_degrade1"))

    assert saver.reads == 1
    assert result.finish_reason == "stop"
    assert [message["role"] for message in result.messages] == ["user", "assistant"]
