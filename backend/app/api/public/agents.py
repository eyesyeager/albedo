import asyncio
import json
import logging
import time
from typing import Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.public.deps import require_agent
from app.api.public.runtime import (
    execute_agent_run,
    persist_failure_logs,
    persist_result,
    prepare_agent_run,
    spawn_interrupted_persist,
)
from app.core.errors import ApiException
from app.core.ratelimit import allow_qpm
from app.core.security import new_id, now_iso
from app.db import models as m
from app.db.session import get_db
from app.runtime import get_runtime_engine
from app.runtime.event_store import append_events, load_events
from app.services.system_config import limit as config_limit

logger = logging.getLogger("albedo.public.agents")

chat_router = APIRouter()
agui_router = APIRouter()


class ChatMessageInput(BaseModel):
    role: str
    content: str = Field(default="", max_length=100_000)


class PublicChatRequest(BaseModel):
    messages: list[ChatMessageInput] = Field(..., max_length=100)
    threadId: str | None = None
    runId: str | None = None
    variables: dict[str, str] = Field(default_factory=dict)
    stream: bool = False
    includeUsage: bool = True


class AGUIRunRequest(BaseModel):
    threadId: str
    runId: str
    messages: list[ChatMessageInput] = Field(default_factory=list, max_length=100)
    tools: list[dict[str, Any]] = Field(default_factory=list)
    context: dict[str, Any] = Field(default_factory=dict)
    state: dict[str, Any] = Field(default_factory=dict)
    forwardedProps: dict[str, Any] = Field(default_factory=dict)


def last_user_content(messages: list[ChatMessageInput]) -> str:
    for message in reversed(messages):
        if message.role == "user" and message.content.strip():
            return message.content
    raise ApiException("invalid_request", "messages 至少需要一条非空 user 内容", 400)


def sse(event: str, payload: dict[str, Any], event_id: int | None = None) -> str:
    # id 让标准 EventSource 断线后自动携带 Last-Event-ID；retry 控制重连间隔。
    head = f"id: {event_id}\n" if event_id is not None else ""
    return f"{head}retry: 3000\nevent: {event}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"


def completion_payload(result: Any, agent_id: str, include_usage: bool) -> dict[str, Any]:
    reply = next((item for item in reversed(result.messages) if item["role"] == "assistant"), None)
    payload: dict[str, Any] = {
        "id": new_id("chatcmpl_", 8),
        "object": "agent.chat.completion",
        "created": int(time.time()),
        "agentId": agent_id,
        "threadId": result.thread_id,
        "runId": result.run_id,
        "status": "completed",
        "message": {
            "id": (reply or {}).get("id", new_id("msg-")),
            "role": "assistant",
            "content": (reply or {}).get("content", ""),
        },
        "finishReason": "tool_limit" if result.finish_reason == "tool_limit" else "stop",
        "metadata": {"durationMs": result.duration_ms, "toolCallCount": result.tool_call_count},
    }
    if include_usage:
        payload["usage"] = result.usage
    return payload


def final_assistant_text(result: Any) -> str:
    """从运行结果取最后一条 assistant 文本，供流式无正文增量时兜底。"""
    if result is None:
        return ""
    for message in reversed(getattr(result, "messages", None) or []):
        if message.get("role") == "assistant" and str(message.get("content") or "").strip():
            return str(message["content"])
    return ""


async def chat_stream(db: AsyncSession, agent: m.Agent, runtime_request: Any, snapshot: dict):
    """真增量 SSE：逐 token 消费引擎事件，不等待整轮运行完成。"""
    message_id = ""
    ended = False
    sent_delta = False
    persisted = False
    pending_error: dict[str, Any] | None = None
    started = time.perf_counter()
    engine_events = get_runtime_engine().run_stream(runtime_request)
    try:
        yield ": keep-alive\n\n"
        async for engine_event in engine_events:
            kind = engine_event.get("type")
            if kind == "heartbeat":
                yield ": keep-alive\n\n"
                continue
            if kind == "run_started":
                message_id = new_id("msg-")
                yield sse("message.start", {"type": "message.start", "messageId": message_id, "role": "assistant"})
            elif kind == "thinking_delta":
                # 推理模型的思考增量必须与正文分开转发，否则整轮推理期间 SSE 静默。
                yield sse("message.thinking", {"type": "message.thinking", "delta": engine_event.get("delta", "")})
            elif kind == "message_start":
                if not message_id:
                    message_id = engine_event["messageId"]
                    yield sse("message.start", {"type": "message.start", "messageId": message_id, "role": "assistant"})
            elif kind == "token":
                sent_delta = True
                current = message_id or engine_event["messageId"]
                yield sse("message.delta", {"type": "message.delta", "messageId": current, "delta": engine_event["delta"]})
            elif kind == "message_end":
                yield sse("message.end", {"type": "message.end", "messageId": message_id or engine_event["messageId"]})
                ended = True
                message_id = ""
            elif kind in ("run_finished", "run_error"):
                result = engine_event.get("result")
                if result is not None:
                    await persist_result(db, agent, result, snapshot, "standard_api")
                else:
                    # 图尚未开始就失败（如上游凭据缺失），仍要落一条失败审计日志。
                    await persist_failure_logs(
                        db, agent, runtime_request,
                        list(engine_event.get("logs") or []),
                        int(engine_event.get("durationMs") or 0),
                        snapshot,
                    )
                persisted = True
                # 只输出 reasoning / 运行超时时没有正文 token，补发最终结果或终止说明，
                # 否则流式客户端只能拿到空消息。
                if not sent_delta:
                    fallback = final_assistant_text(result)
                    if fallback:
                        yield sse("message.delta", {
                            "type": "message.delta", "messageId": message_id or new_id("msg-"), "delta": fallback,
                        })
                        sent_delta = True
                if kind == "run_error":
                    pending_error = {
                        "type": "message.error",
                        "messageId": message_id,
                        "code": engine_event.get("code", "agent_run_failed"),
                        "message": engine_event.get("message", "运行失败"),
                    }
        # 正常路径收尾：run_error 帧在此统一输出。
        if pending_error is not None:
            yield sse("message.error", pending_error)
        if not ended or pending_error is not None:
            yield sse("message.end", {"type": "message.end", "messageId": message_id or new_id("msg-")})
    except Exception as exc:  # noqa: BLE001
        error = {"type": "message.error", "messageId": message_id, "code": "agent_run_failed", "message": str(exc)[:200]}
        yield sse("message.error", error)
        yield sse("message.end", {"type": "message.end", "messageId": message_id or new_id("msg-")})
    # 收尾帧不放在 finally：GeneratorExit / CancelledError 会直接跳到 finally，
    # 在那里 yield 会抛 "async generator ignored GeneratorExit"。
    finally:
        try:
            await engine_events.aclose()
        except (GeneratorExit, RuntimeError, asyncio.CancelledError):
            pass
        except Exception as exc:  # noqa: BLE001
            logger.warning("关闭运行事件流失败：%s", exc)
        if not persisted:
            spawn_interrupted_persist(
                agent.id, runtime_request, snapshot, "standard_api",
                int((time.perf_counter() - started) * 1000),
            )


def to_agui_events(event: dict[str, Any]) -> list[dict[str, Any]]:
    kind = event.get("type")
    if kind == "run_started":
        return [{"type": "RUN_STARTED"}]
    if kind == "message_start":
        return [{"type": "TEXT_MESSAGE_START", "messageId": event["messageId"], "role": "assistant"}]
    if kind == "token":
        return [{"type": "TEXT_MESSAGE_CONTENT", "messageId": event["messageId"], "delta": event["delta"]}]
    if kind == "message_end":
        return [{"type": "TEXT_MESSAGE_END", "messageId": event["messageId"]}]
    if kind == "tool_start":
        return [
            {"type": "TOOL_CALL_START", "toolCallId": event["toolCallId"], "toolCallName": event["name"]},
            {"type": "TOOL_CALL_ARGS", "toolCallId": event["toolCallId"], "args": event.get("args") or {}},
        ]
    if kind == "tool_result":
        return [
            {"type": "TOOL_CALL_END", "toolCallId": event["toolCallId"]},
            {
                "type": "TOOL_CALL_RESULT",
                "toolCallId": event["toolCallId"],
                "status": event.get("status", "success"),
                "content": {"summary": event.get("summary", "")},
            },
        ]
    if kind == "run_finished":
        result = event["result"]
        return [{
            "type": "RUN_FINISHED",
            "status": "completed",
            "usage": result.usage,
            "durationMs": result.duration_ms,
            "finishReason": result.finish_reason,
        }]
    if kind == "run_error":
        return [{"type": "RUN_ERROR", "code": event.get("code", "agent_run_failed"), "message": event.get("message", "运行失败")}]
    return []


async def agui_stream(db: AsyncSession, agent: m.Agent, runtime_request: Any, snapshot: dict, thread_id: str):
    sequence = 0
    pending: list[dict[str, Any]] = []
    thinking_id = ""
    sent_text = False
    persisted = False
    started = time.perf_counter()
    engine_events = get_runtime_engine().run_stream(runtime_request)

    async def emit(payload: dict[str, Any]) -> dict[str, Any]:
        nonlocal sequence
        sequence += 1
        event = {
            "threadId": thread_id,
            "runId": runtime_request.run_id,
            "timestamp": now_iso(),
            "sequence": sequence,
            **payload,
        }
        pending.append(event)
        if len(pending) >= 8:
            await append_events(runtime_request.run_id, pending)
            pending.clear()
        return event

    async def push(payload: dict[str, Any]) -> str:
        event = await emit(payload)
        return sse(event["type"], event, int(event["sequence"]))

    async def close_thinking() -> list[str]:
        nonlocal thinking_id
        if not thinking_id:
            return []
        frame = await push({"type": "THINKING_TEXT_MESSAGE_END", "messageId": thinking_id})
        thinking_id = ""
        return [frame]

    try:
        yield ": keep-alive\n\n"
        async for engine_event in engine_events:
            kind = engine_event.get("type", "")
            if kind == "heartbeat":
                yield ": keep-alive\n\n"
                continue
            # AGUI v1 的思考消息与正式回答分开发送，避免推理阶段整段静默。
            if kind == "thinking_delta":
                delta = str(engine_event.get("delta") or "")
                if not delta:
                    continue
                if not thinking_id:
                    thinking_id = new_id("msg-")
                    yield await push({
                        "type": "THINKING_TEXT_MESSAGE_START", "messageId": thinking_id, "role": "assistant",
                    })
                yield await push({"type": "THINKING_TEXT_MESSAGE_CONTENT", "messageId": thinking_id, "delta": delta})
                continue
            for frame in await close_thinking():
                yield frame
            if kind == "token":
                sent_text = True
            if kind in ("run_finished", "run_error"):
                result = engine_event.get("result")
                if result is not None:
                    await persist_result(db, agent, result, snapshot, "agui")
                else:
                    await persist_failure_logs(
                        db, agent, runtime_request,
                        list(engine_event.get("logs") or []),
                        int(engine_event.get("durationMs") or 0),
                        snapshot,
                    )
                persisted = True
                # 推理模型只输出 reasoning_content、或运行在正文出现前超时，流式里没有任何
                # TEXT_MESSAGE_CONTENT。此时把最终结果 / 终止说明补成一条完整文本消息，
                # 保证 AGUI 客户端一定能拿到响应内容。
                if not sent_text:
                    fallback = final_assistant_text(result)
                    if fallback:
                        message_id = new_id("msg-")
                        yield await push({"type": "TEXT_MESSAGE_START", "messageId": message_id, "role": "assistant"})
                        yield await push({"type": "TEXT_MESSAGE_CONTENT", "messageId": message_id, "delta": fallback})
                        yield await push({"type": "TEXT_MESSAGE_END", "messageId": message_id})
                        sent_text = True
            for payload in to_agui_events(engine_event):
                yield await push(payload)
    except Exception as exc:  # noqa: BLE001
        for frame in await close_thinking():
            yield frame
        yield await push({"type": "RUN_ERROR", "code": "agent_run_failed", "message": str(exc)[:200]})
    finally:
        if pending:
            await append_events(runtime_request.run_id, pending)
            pending.clear()
        try:
            await engine_events.aclose()
        except (GeneratorExit, RuntimeError, asyncio.CancelledError):
            pass
        except Exception as exc:  # noqa: BLE001
            logger.warning("关闭运行事件流失败：%s", exc)
        if not persisted:
            spawn_interrupted_persist(
                agent.id, runtime_request, snapshot, "agui",
                int((time.perf_counter() - started) * 1000),
            )


async def replay_agui(events: list[dict[str, Any]], last_event_id: int):
    for event in events:
        if int(event.get("sequence") or 0) <= last_event_id:
            continue
        yield sse(str(event.get("type") or "MESSAGE"), event, int(event.get("sequence") or 0))


async def load_agent_context(
    db: AsyncSession,
    agent: m.Agent,
    *,
    expected_id: str,
    protocol: str,
) -> m.ModelResource:
    if agent.id != expected_id:
        raise ApiException("agent_access_denied", "当前 Key 无权调用该智能体", 403)
    if protocol == "api" and not agent.api_enabled:
        raise ApiException("agent_not_published", "该智能体的标准对话 API 尚未开启", 409)
    if protocol == "agui" and not agent.agui_enabled:
        raise ApiException("agent_not_published", "该智能体的 AGUI 接口尚未开启", 409)
    model = await db.get(m.ModelResource, agent.default_model_id)
    if model is None:
        raise ApiException("agent_not_published", "智能体尚未配置默认模型", 409)
    return model


@chat_router.post("/agents/{agent_id}/chat")
async def agent_chat(
    agent_id: str,
    payload: PublicChatRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
):
    agent, _ = await require_agent(request, db)
    model = await load_agent_context(db, agent, expected_id=agent_id, protocol="api")
    if not await allow_qpm(f"agent:{agent.id}", int(config_limit("defaultAgentQpm", 60))):
        raise ApiException("rate_limit_exceeded", "超过 Agent 调用额度，请稍后重试", 429)

    content = last_user_content(payload.messages)
    if payload.stream:
        runtime_request, snapshot = await prepare_agent_run(
            db, agent, model,
            content=content,
            variables=payload.variables,
            thread_id=payload.threadId,
            run_id=payload.runId,
            source="standard_api",
        )
        return StreamingResponse(
            chat_stream(db, agent, runtime_request, snapshot),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )
    result = await execute_agent_run(
        db, agent, model,
        content=content,
        variables=payload.variables,
        thread_id=payload.threadId,
        run_id=payload.runId,
        source="standard_api",
    )
    return completion_payload(result, agent.id, payload.includeUsage)


@agui_router.post("/agents/{agent_id}/run")
async def agui_run(
    agent_id: str,
    payload: AGUIRunRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
):
    agent, _ = await require_agent(request, db)
    model = await load_agent_context(db, agent, expected_id=agent_id, protocol="agui")
    if not await allow_qpm(f"agent:{agent.id}", int(config_limit("defaultAgentQpm", 60))):
        raise ApiException("rate_limit_exceeded", "超过 Agent 调用额度，请稍后重试", 429)

    variables = (payload.context or {}).get("variables") or payload.forwardedProps.get("variables") or {}
    try:
        last_event_id = int(request.headers.get("Last-Event-ID", "0") or 0)
    except ValueError:
        last_event_id = 0
    if last_event_id and payload.runId:
        # 事件缓存只按 runId 索引，回放前必须确认该 run 属于当前凭据绑定的智能体
        # 与请求中的会话，否则任一 Token 都能猜测 runId 读取其它智能体的事件流。
        run = await db.get(m.Run, payload.runId)
        if run is None or run.agent_id != agent.id or run.thread_id != payload.threadId:
            raise ApiException("run_not_found", "未找到属于当前智能体与会话的运行记录", 404)
        cached = await load_events(payload.runId)
        if cached:
            # 断线重连只回放已缓存事件，不要求再次携带 messages
            return StreamingResponse(
                replay_agui(cached, last_event_id),
                media_type="text/event-stream",
                headers={"Cache-Control": "no-cache", "X-AGUI-Version": "1", "X-Accel-Buffering": "no"},
            )
    content = last_user_content(payload.messages)
    runtime_request, snapshot = await prepare_agent_run(
        db, agent, model,
        content=content,
        variables=variables,
        thread_id=payload.threadId,
        run_id=payload.runId,
        source="agui",
    )
    return StreamingResponse(
        agui_stream(db, agent, runtime_request, snapshot, payload.threadId),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-AGUI-Version": "1", "X-Accel-Buffering": "no"},
    )
