import json

import pytest
from httpx import AsyncClient
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, ToolMessage
from langchain_core.messages.tool import invalid_tool_call
from langchain_core.outputs import ChatGeneration, ChatResult
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
    login = await client.post("/v1/auth/login", json={"key": DEMO_KEY})
    assert login.status_code == 200

    response = await client.post("/v1/agents/agt-research/test-runs", json={
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

    logs = await client.get("/v1/logs", params={"keyword": body["runId"], "range": "1y"})
    assert logs.json()["total"] >= 3

    detail = await client.get("/v1/workspace/bootstrap")
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


@pytest.mark.asyncio
async def test_skill_instructions_runner_keeps_full_text_and_reports_truncation() -> None:
    from app.runtime.tools import skill_instructions_runner

    text = "指令段落。" * 800  # 4000 字

    full = json.loads(await skill_instructions_runner("t0_skill", text, 40000)())
    assert full["status"] == "loaded"
    assert full["truncated"] is False
    assert full["totalChars"] == len(text)
    assert full["instructions"] == text
    assert "已全量提供" in full["summary"]

    cut = json.loads(await skill_instructions_runner("t0_skill", text, 1000)())
    assert cut["truncated"] is True
    assert len(cut["instructions"]) == 1000
    assert cut["totalChars"] == len(text)
    assert "本次提供前 1000 字" in cut["summary"]


def test_normalize_invalid_tool_calls() -> None:
    """非法工具调用应被归一化为合法调用，避免非法 arguments 回传上游触发 400。"""
    from app.runtime.engine import normalize_invalid_tool_calls

    message = AIMessage(
        content="",
        tool_calls=[{"name": "t0_web_search", "args": {"query": "x"}, "id": "call_ok"}],
        invalid_tool_calls=[invalid_tool_call(name="search", args="", id=None)],
    )
    normalized = normalize_invalid_tool_calls(message)

    assert normalized.invalid_tool_calls == []
    assert [call["name"] for call in normalized.tool_calls] == ["t0_web_search", "search"]
    assert normalized.tool_calls[1]["args"] == {"status": "error", "message": "工具调用参数不是合法 JSON，未执行"}
    assert normalized.tool_calls[1]["id"] == "call_invalid_0"

    # 无非法调用时原样返回，不做多余复制
    clean = AIMessage(content="hi")
    assert normalize_invalid_tool_calls(clean) is clean


class _InvalidToolCallModel(BaseChatModel):
    """第一轮返回一个合法 + 一个非法工具调用，验证引擎不崩且回传已归一化。"""

    received: list[list] = []

    @property
    def _llm_type(self) -> str:
        return "albedo-test-invalid-tool-call"

    def bind_tools(self, tools, **kwargs):  # type: ignore[override]
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):  # type: ignore[override]
        self.received.append(list(messages))
        executed = sum(1 for message in messages if isinstance(message, ToolMessage))
        if executed == 0:
            reply = AIMessage(
                content="",
                tool_calls=[{"name": "t0_web_search", "args": {"query": "x"}, "id": "call_ok"}],
                invalid_tool_calls=[invalid_tool_call(name="search", args="", id=None)],
            )
        else:
            reply = AIMessage(content="已完成")
        return ChatResult(generations=[ChatGeneration(message=reply)])


@pytest.mark.asyncio
async def test_engine_survives_invalid_tool_calls() -> None:
    model = _InvalidToolCallModel()
    engine = RuntimeEngine(
        checkpointer=InMemorySaver(),
        model_factory=lambda snapshot, tools, prompt: model,
    )
    snapshot = make_snapshot()
    snapshot["resolvedMounts"]["tool"] = [
        {"id": "tool-web", "name": "联网搜索", "key": "web_search", "description": "检索网页", "enabled": True},
    ]

    result = await engine.run(request("请检索资料", snapshot, run_id="run_invalid1"))

    assert result.finish_reason == "stop"
    # 第一轮产生工具调用，第二轮回传历史，共两次模型调用
    assert len(model.received) == 2
    # 回传的 AIMessage 不应再携带非法工具调用（已归一化，杜绝非法 arguments）
    for message in model.received[1]:
        if isinstance(message, AIMessage):
            assert not getattr(message, "invalid_tool_calls", None)
    # 非法调用应生成一条明确的失败反馈，供模型自纠
    tool_messages = [message for message in model.received[1] if isinstance(message, ToolMessage)]
    assert any("参数不是合法 JSON" in str(message.content) for message in tool_messages)
