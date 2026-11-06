import asyncio
import json
import time
from typing import Any, AsyncIterator, Callable

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import BaseTool
from langgraph.graph import END, START, StateGraph

from app.core.errors import ApiException
from app.core.security import new_id, now_iso
from app.runtime.checkpointer import degrade_to_memory, get_checkpointer, is_checkpointer_error
from app.runtime.live_model import build_live_model, invoke_with_retry, is_retryable, map_upstream_error
from app.runtime.prompts import render_system_prompt
from app.runtime.state import AgentState
from app.runtime.tools import build_tools, collect_tool_specs
from app.runtime.types import RuntimeRequest, RuntimeResult, ToolSpec
from app.services.audit import make_log_entry
from app.services.tool_usage import record_in_new_session

SOURCE_LABELS = {
    "test_session": "测试会话",
    "standard_api": "标准 API",
    "agui": "AGUI 会话",
    "system": "系统",
}


class _Collector:
    def __init__(self) -> None:
        self.tool_events: list[dict[str, Any]] = []
        self.model_spans: list[dict[str, Any]] = []


class RuntimeEngine:
    """M5a-2 LangGraph 运行引擎：prepare → model → tools → finalize。

    - 快照冻结：所有配置由调用方在入图前组装，图内不读库
    - 变量注入：系统变量 → 请求变量 → 缺失校验
    - 终止条件：maxToolCalls（条件边）、maxRunSeconds（asyncio.wait_for）
    - 模型：ChatOpenAI，始终调用真实上游
    - 工具：内置工具与 MCP 均为真实执行，无离线模拟
    - checkpointer：Redis 优先、失败降级内存
    """

    def __init__(
        self,
        checkpointer: Any | None = None,
        model_factory: Callable[[dict, list[str], str], Any] | None = None,
    ) -> None:
        self._checkpointer = checkpointer
        self._model_factory = model_factory

    async def _get_checkpointer(self) -> Any:
        if self._checkpointer is None:
            self._checkpointer = await get_checkpointer()
        return self._checkpointer

    async def _degrade_checkpointer(self, exc: BaseException) -> bool:
        """checkpointer 存储层失效时降级为内存实现，返回是否已降级。"""
        if not is_checkpointer_error(exc):
            return False
        await degrade_to_memory(f"{type(exc).__name__}: {str(exc)[:160]}")
        self._checkpointer = None
        return True

    async def clear_thread(self, thread_id: str) -> None:
        saver = await self._get_checkpointer()
        delete = getattr(saver, "adelete_thread", None)
        if delete is not None:
            try:
                await delete(thread_id)
                return
            except Exception:  # noqa: BLE001
                pass
        storage = getattr(saver, "storage", None)
        if isinstance(storage, dict):
            for key in [key for key in storage if str(key) == thread_id]:
                storage.pop(key, None)

    def _build_model(self, snapshot: dict, tools: list[BaseTool], rendered: str) -> Any:
        tool_names = [tool.name for tool in tools]
        if self._model_factory is not None:
            return self._model_factory(snapshot, tool_names, rendered)
        return build_live_model(snapshot, tool_names, rendered)

    async def _prepare_run(self, request: RuntimeRequest, *, streaming: bool, retried: bool = False) -> dict[str, Any]:
        snapshot = request.snapshot
        advanced: dict[str, Any] = snapshot.get("advanced") or {}
        max_tool_calls = max(1, min(100, int(advanced.get("maxToolCalls", 10))))
        max_run_seconds = max(0.05, float(advanced.get("maxRunSeconds", 120)))
        keep_context = bool(advanced.get("keepContext", True))
        thread_id = request.thread_id if keep_context else f"{request.thread_id}_{request.run_id}"

        rendered, missing = render_system_prompt(snapshot, request.variables, strict=request.strict_variables)
        specs = collect_tool_specs(snapshot)
        tools, spec_by_name = await build_tools(specs, snapshot)
        model = self._build_model(snapshot, tools, rendered)
        if tools and hasattr(model, "bind_tools"):
            model = model.bind_tools(tools)

        collector = _Collector()
        checkpointer = await self._get_checkpointer()
        graph = self._build_graph(
            model,
            tools,
            spec_by_name,
            collector,
            checkpointer,
            auto_retry=bool(advanced.get("autoRetry", True)),
            streaming=streaming,
            run_id=request.run_id,
        )
        config = {"configurable": {"thread_id": thread_id}}
        before = None
        if keep_context:
            try:
                before = await graph.aget_state(config)
            except Exception as exc:  # noqa: BLE001
                if retried or not await self._degrade_checkpointer(exc):
                    raise
                return await self._prepare_run(request, streaming=streaming, retried=True)
        history_len = len(before.values.get("messages", [])) if before and before.values else 0

        payload: AgentState = {
            "messages": [HumanMessage(content=request.content)],
            "system_prompt": rendered,
            "tool_call_count": 0,
            "finish_reason": "stop",
            "usage": {},
            "started_at": time.time(),
            "agent_name": str(snapshot.get("name") or ""),
            "model_name": str((snapshot.get("model") or {}).get("name") or ""),
            "max_tool_calls": max_tool_calls,
            "max_run_seconds": max_run_seconds,
        }
        return {
            "snapshot": snapshot,
            "advanced": advanced,
            "max_tool_calls": max_tool_calls,
            "max_run_seconds": max_run_seconds,
            "thread_id": thread_id,
            "rendered": rendered,
            "missing": missing,
            "spec_by_name": spec_by_name,
            "collector": collector,
            "graph": graph,
            "config": config,
            "history_len": history_len,
            "payload": payload,
        }

    def _build_result(
        self,
        request: RuntimeRequest,
        ctx: dict[str, Any],
        messages: list,
        finish_reason: str,
        duration_ms: int,
        *,
        usage: dict[str, int] | None = None,
        streamed_message_id: str = "",
    ) -> RuntimeResult:
        usage = dict(usage or {})
        if not usage.get("totalTokens"):
            usage = self._usage(ctx["snapshot"], ctx["rendered"], request.content, messages)
        new_messages = messages[ctx["history_len"]:]
        chat_messages = self._to_chat_messages(
            new_messages, ctx["collector"], request, ctx["advanced"], finish_reason, ctx["max_run_seconds"],
        )
        if streamed_message_id:
            for message in reversed(chat_messages):
                if message["role"] == "assistant":
                    message["id"] = streamed_message_id
                    break
        logs = self._build_logs(
            request, ctx["snapshot"], ctx["collector"], duration_ms, finish_reason,
            usage, ctx["missing"], ctx["max_tool_calls"],
        )
        return RuntimeResult(
            run_id=request.run_id,
            thread_id=ctx["thread_id"],
            messages=chat_messages,
            logs=logs,
            finish_reason=finish_reason,
            usage=usage,
            duration_ms=duration_ms,
            tool_call_count=len(ctx["collector"].tool_events),
            missing_variables=ctx["missing"],
        )

    async def _ainvoke(self, ctx: dict[str, Any]) -> dict[str, Any]:
        try:
            return await asyncio.wait_for(
                ctx["graph"].ainvoke(ctx["payload"], ctx["config"]),
                timeout=ctx["max_run_seconds"],
            )
        except (asyncio.TimeoutError, TimeoutError):
            values: dict[str, Any] = {}
            try:
                state = await ctx["graph"].aget_state(ctx["config"])
                values = state.values if state and state.values else {}
            except Exception:  # noqa: BLE001
                pass
            return {"messages": values.get("messages", []), "finish_reason": "timeout", "usage": values.get("usage") or {}}

    async def run(self, request: RuntimeRequest) -> RuntimeResult:
        ctx = await self._prepare_run(request, streaming=False)
        started = time.perf_counter()
        try:
            try:
                final = await self._ainvoke(ctx)
            except Exception as exc:  # noqa: BLE001
                # 尚未产生任何模型输出时，checkpointer 失效可安全降级重跑
                if ctx["collector"].model_spans or not await self._degrade_checkpointer(exc):
                    raise
                ctx = await self._prepare_run(request, streaming=False, retried=True)
                final = await self._ainvoke(ctx)
        except Exception as exc:  # noqa: BLE001
            mapped = map_upstream_error(exc)
            if mapped is not None:
                raise mapped from exc
            raise
        duration_ms = int((time.perf_counter() - started) * 1000)
        finish_reason = str(final.get("finish_reason") or "stop")
        return self._build_result(
            request, ctx, list(final.get("messages") or []), finish_reason, duration_ms,
            usage=dict(final.get("usage") or {}),
        )

    async def run_stream(self, request: RuntimeRequest) -> AsyncIterator[dict[str, Any]]:
        """真流式运行：token / 工具事件实时产出，结束后附带完整 RuntimeResult。"""
        ctx = await self._prepare_run(request, streaming=True)
        started = time.perf_counter()
        message_id = ""
        message_open = False
        produced = False
        yield {"type": "run_started", "runId": request.run_id, "threadId": ctx["thread_id"]}

        attempt = 0
        while True:
            graph = ctx["graph"]
            finish_reason = "stop"
            error: ApiException | None = None
            iterator = None
            deadline = asyncio.get_running_loop().time() + ctx["max_run_seconds"]
            try:
                iterator = graph.astream_events(ctx["payload"], ctx["config"], version="v2").__aiter__()
                while True:
                    remaining = deadline - asyncio.get_running_loop().time()
                    if remaining <= 0:
                        raise asyncio.TimeoutError
                    try:
                        event = await asyncio.wait_for(iterator.__anext__(), timeout=remaining)
                    except StopAsyncIteration:
                        break
                    kind = event.get("event")
                    data = event.get("data") or {}
                    if kind == "on_chat_model_stream":
                        text = self._chunk_text(data.get("chunk"))
                        if text:
                            produced = True
                            if not message_open:
                                message_id = new_id("msg-")
                                message_open = True
                                yield {"type": "message_start", "messageId": message_id}
                            yield {"type": "token", "messageId": message_id, "delta": text}
                    elif kind == "on_tool_start":
                        produced = True
                        if message_open:
                            yield {"type": "message_end", "messageId": message_id}
                            message_open = False
                        tool_name = str(event.get("name") or "")
                        spec = ctx["spec_by_name"].get(tool_name)
                        yield {
                            "type": "tool_start",
                            "toolCallId": str(event.get("run_id") or ""),
                            "name": spec.name if spec else tool_name,
                            "args": data.get("input") if isinstance(data.get("input"), dict) else {},
                        }
                    elif kind == "on_tool_end":
                        produced = True
                        output = data.get("output")
                        content = getattr(output, "content", output)
                        yield {
                            "type": "tool_result",
                            "toolCallId": str(event.get("run_id") or ""),
                            "status": "success",
                            "summary": self._summarize_result(content),
                        }
                    elif kind == "on_tool_error":
                        produced = True
                        yield {
                            "type": "tool_result",
                            "toolCallId": str(event.get("run_id") or ""),
                            "status": "failed",
                            "summary": str(data.get("error") or "工具执行失败")[:200],
                        }
            except (asyncio.TimeoutError, TimeoutError):
                finish_reason = "timeout"
                error = ApiException("agent_run_timeout", "运行超过最大时长限制，已终止", 504)
            except Exception as exc:  # noqa: BLE001
                # 尚未向客户端产出任何事件时，checkpointer 失效可降级重跑一次
                if not produced and attempt == 0 and await self._degrade_checkpointer(exc):
                    attempt = 1
                    ctx = await self._prepare_run(request, streaming=True, retried=True)
                    continue
                mapped = map_upstream_error(exc)
                error = mapped or ApiException("agent_run_failed", f"运行失败：{str(exc)[:200]}", 500)
                finish_reason = "failed"
            finally:
                if iterator is not None:
                    try:
                        await iterator.aclose()
                    except Exception:  # noqa: BLE001
                        pass
            break

        values: dict[str, Any] = {}
        try:
            state = await ctx["graph"].aget_state(ctx["config"])
            values = state.values if state and state.values else {}
        except Exception:  # noqa: BLE001
            pass
        messages = list(values.get("messages") or [])
        usage = dict(values.get("usage") or {})
        duration_ms = int((time.perf_counter() - started) * 1000)
        if message_open:
            yield {"type": "message_end", "messageId": message_id}
        if error is not None:
            result = self._build_result(
                request, ctx, messages, finish_reason, duration_ms,
                usage=usage, streamed_message_id=message_id,
            )
            yield {"type": "run_error", "code": error.code, "message": error.message, "result": result}
            return
        finish_reason = str(values.get("finish_reason") or "stop")
        result = self._build_result(
            request, ctx, messages, finish_reason, duration_ms,
            usage=usage, streamed_message_id=message_id,
        )
        yield {"type": "run_finished", "result": result}

    def _build_graph(
        self,
        model: Any,
        tools: list[BaseTool],
        spec_by_name: dict[str, ToolSpec],
        collector: _Collector,
        checkpointer: Any,
        *,
        auto_retry: bool,
        streaming: bool = False,
        run_id: str = "",
    ):
        tools_by_name = {tool.name: tool for tool in tools}
        max_retries = 2 if auto_retry else 0

        async def prepare(state: AgentState) -> dict:
            return {"tool_call_count": 0, "finish_reason": "stop", "started_at": time.time()}

        async def model_node(state: AgentState) -> dict:
            started = time.perf_counter()
            messages = [SystemMessage(content=state["system_prompt"])] + list(state["messages"])
            if streaming:
                reply = await self._stream_model(model, messages, max_retries)
            else:
                reply = await invoke_with_retry(model, messages, max_retries=max_retries)
            collector.model_spans.append({
                "duration_ms": int((time.perf_counter() - started) * 1000),
                "content": str(reply.content),
                "tool_calls": len(getattr(reply, "tool_calls", []) or []),
            })
            usage = dict(state.get("usage") or {"inputTokens": 0, "outputTokens": 0, "totalTokens": 0})
            meta = getattr(reply, "usage_metadata", None) or {}
            usage["inputTokens"] = int(usage.get("inputTokens", 0)) + int(meta.get("input_tokens", 0) or 0)
            usage["outputTokens"] = int(usage.get("outputTokens", 0)) + int(meta.get("output_tokens", 0) or 0)
            usage["totalTokens"] = int(usage.get("totalTokens", 0)) + int(meta.get("total_tokens", 0) or 0)
            return {"messages": [reply], "usage": usage}

        async def tools_node(state: AgentState) -> dict:
            last = state["messages"][-1]
            calls = list(getattr(last, "tool_calls", []) or [])
            remaining = max(0, state["max_tool_calls"] - state["tool_call_count"])
            allowed = calls[:remaining]
            skipped = len(calls) - len(allowed)
            results: list[ToolMessage] = []
            for call in allowed:
                tool_name = str(call.get("name") or "")
                tool = tools_by_name.get(tool_name)
                spec = spec_by_name.get(tool_name)
                started = time.perf_counter()
                status = "success"
                if tool is None:
                    output = json.dumps({"status": "error", "message": "工具未注册"}, ensure_ascii=False)
                    status = "failed"
                else:
                    try:
                        output = await tool.ainvoke(call.get("args") or {})
                    except Exception as exc:  # noqa: BLE001
                        output = json.dumps({"status": "error", "message": str(exc)[:200]}, ensure_ascii=False)
                        status = "failed"
                duration_ms = int((time.perf_counter() - started) * 1000)
                summary = self._summarize_result(output)
                await record_in_new_session(
                    kind=spec.kind if spec else "tool",
                    resource_id=spec.resource_id if spec else "",
                    tool_key=spec.key.split(":", 2)[-1] if spec else tool_name,
                    status=status,
                    duration_ms=duration_ms,
                    run_id=run_id,
                )
                collector.tool_events.append({
                    "timestamp": now_iso(),
                    "kind": spec.kind if spec else "tool",
                    "name": spec.name if spec else tool_name,
                    "resource_id": spec.resource_id if spec else "",
                    "args": call.get("args") or {},
                    "status": status,
                    "duration_ms": duration_ms,
                    "summary": summary,
                    "output": str(output)[:500],
                })
                results.append(ToolMessage(
                    content=str(output),
                    tool_call_id=str(call.get("id") or ""),
                    name=tool_name,
                ))
            return {
                "messages": results,
                "tool_call_count": state["tool_call_count"] + len(allowed),
                "finish_reason": "tool_limit" if skipped else state["finish_reason"],
            }

        async def finalize(state: AgentState) -> dict:
            last = state["messages"][-1] if state["messages"] else None
            finish = state["finish_reason"]
            if isinstance(last, AIMessage) and getattr(last, "tool_calls", None) and not str(last.content).strip():
                finish = "tool_limit"
                return {
                    "messages": [AIMessage(content=f"已达到最大工具调用次数（{state['max_tool_calls']} 次），本次运行在此终止。")],
                    "finish_reason": finish,
                }
            return {"finish_reason": finish}

        def route(state: AgentState) -> str:
            last = state["messages"][-1] if state["messages"] else None
            if not getattr(last, "tool_calls", None):
                return "finalize"
            if state["tool_call_count"] >= state["max_tool_calls"]:
                return "finalize"
            return "tools"

        builder = StateGraph(AgentState)
        builder.add_node("prepare", prepare)
        builder.add_node("model", model_node)
        builder.add_node("tools", tools_node)
        builder.add_node("finalize", finalize)
        builder.add_edge(START, "prepare")
        builder.add_edge("prepare", "model")
        builder.add_conditional_edges("model", route, {"tools": "tools", "finalize": "finalize"})
        builder.add_edge("tools", "model")
        builder.add_edge("finalize", END)
        return builder.compile(checkpointer=checkpointer)

    @staticmethod
    async def _stream_model(model: Any, messages: list, max_retries: int) -> Any:
        chunks: list[Any] = []
        attempt = 0
        while True:
            try:
                async for chunk in model.astream(messages):
                    chunks.append(chunk)
                break
            except Exception as exc:  # noqa: BLE001
                if chunks or attempt >= max_retries or not is_retryable(exc):
                    raise
                attempt += 1
                await asyncio.sleep(0.6 * (2 ** attempt))
        if not chunks:
            return AIMessage(content="")
        reply = chunks[0]
        for chunk in chunks[1:]:
            reply = reply + chunk
        return reply

    @staticmethod
    def _chunk_text(chunk: Any) -> str:
        if chunk is None:
            return ""
        content = getattr(chunk, "content", None)
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            parts: list[str] = []
            for block in content:
                if isinstance(block, str):
                    parts.append(block)
                elif isinstance(block, dict) and block.get("type") in ("text", "text_delta"):
                    parts.append(str(block.get("text") or block.get("content") or ""))
            return "".join(parts)
        return ""

    @staticmethod
    def _summarize_result(output: Any) -> str:
        try:
            parsed = json.loads(str(output))
            if isinstance(parsed, dict):
                return str(parsed.get("summary") or parsed.get("status") or "工具执行完成")
        except (json.JSONDecodeError, TypeError):
            pass
        return str(output)[:200]

    @staticmethod
    def _usage(snapshot: dict, rendered: str, content: str, messages: list) -> dict[str, int]:
        # 上游未返回 usage_metadata 时不按字符数臆造 token 数，如实返回 0。
        return {"inputTokens": 0, "outputTokens": 0, "totalTokens": 0}

    def _to_chat_messages(
        self,
        messages: list,
        collector: _Collector,
        request: RuntimeRequest,
        advanced: dict,
        finish_reason: str,
        max_run_seconds: float,
    ) -> list[dict[str, Any]]:
        output: list[dict[str, Any]] = []

        def push(role: str, content: str, *, timestamp: str | None = None, **extra: Any) -> None:
            output.append({
                "id": new_id("msg-"),
                "role": role,
                "content": content,
                "timestamp": timestamp or now_iso(),
                **extra,
            })

        push("user", request.content)
        for event in collector.tool_events:
            push(
                "tool",
                "",
                timestamp=event["timestamp"],
                toolCall={
                    "name": event["name"],
                    "args": json.dumps(event["args"], ensure_ascii=False),
                    "status": event["status"],
                    "result": event["summary"],
                },
            )
        if advanced.get("outputThinkingSummary"):
            tool_count = len(collector.tool_events)
            push("assistant", f"思考摘要：已完成 {tool_count} 次工具调用，正在汇总结果并组织回答（不输出模型私有推理链）。")

        for message in messages:
            if isinstance(message, AIMessage) and not getattr(message, "tool_calls", None) and str(message.content).strip():
                push("assistant", str(message.content))

        if finish_reason == "timeout":
            push("assistant", f"本次运行超过 {max_run_seconds:.0f} 秒上限，已终止。已产生的工具调用与日志已保留，可在调用日志中按 runId 查看。")
        if len(output) <= 1:
            push("assistant", "模型未返回有效内容，请检查提示词与模型配置后重试。")
        return output

    def _build_logs(
        self,
        request: RuntimeRequest,
        snapshot: dict,
        collector: _Collector,
        duration_ms: int,
        finish_reason: str,
        usage: dict[str, int],
        missing: list[str],
        max_tool_calls: int,
    ) -> list[dict[str, Any]]:
        logs: list[dict[str, Any]] = []
        index = 0

        for event in collector.tool_events:
            entry = make_log_entry(
                event["name"],
                f"工具调用{'成功' if event['status'] == 'success' else '失败'}（第 {index + 1} 次，上限 {max_tool_calls} 次）",
                log_type=event["kind"],
                source=request.source,
                resource_id=event["resource_id"],
                runId=request.run_id,
                threadId=request.thread_id,
                agentId=request.agent_id,
                durationMs=event["duration_ms"],
                inputSummary=json.dumps(event["args"], ensure_ascii=False),
                outputSummary=event["output"],
            )
            logs.append(entry)
            index += 1

        model = snapshot.get("model") or {}
        params = snapshot.get("modelParams") or {}
        model_log = make_log_entry(
            f"{model.get('name') or '模型'} / 推理",
            f"LangGraph model 节点完成，温度 {params.get('temperature', 0.7)}，工具调用意图 {sum(span['tool_calls'] for span in collector.model_spans)} 次",
            log_type="model",
            source=request.source,
            resource_id=str(model.get("id") or ""),
            runId=request.run_id,
            threadId=request.thread_id,
            agentId=request.agent_id,
            durationMs=sum(span["duration_ms"] for span in collector.model_spans),
            tokenUsage=usage,
            inputSummary=json.dumps({"messages": "[已脱敏]", "stream": False}, ensure_ascii=False),
            outputSummary="已生成最终回复",
        )
        logs.append(model_log)
        index += 1

        status = "success" if finish_reason in ("stop", "tool_limit") else "failed"
        notes = [f"finishReason: {finish_reason}", f"工具调用 {len(collector.tool_events)} 次（上限 {max_tool_calls} 次）"]
        if missing:
            notes.append(f"缺失变量以空值兜底：{', '.join(missing)}")
        if finish_reason == "timeout":
            notes.append("运行超过 maxRunSeconds，已取消图执行")
        agent_name = str(snapshot.get("name") or "智能体")
        label = SOURCE_LABELS.get(request.source, "测试会话")
        session_log = make_log_entry(
            f"{agent_name} / {label}",
            request.content if len(request.content) <= 60 else f"{request.content[:60]}…",
            log_type="session",
            source=request.source,
            status=status,
            runId=request.run_id,
            threadId=request.thread_id,
            agentId=request.agent_id,
            durationMs=duration_ms,
            tokenUsage=usage,
            inputSummary=json.dumps({"message": request.content}, ensure_ascii=False),
            outputSummary="；".join(notes),
        )
        logs.append(session_log)
        return logs


_engine: RuntimeEngine | None = None


def get_runtime_engine() -> RuntimeEngine:
    global _engine
    if _engine is None:
        _engine = RuntimeEngine()
    return _engine
