import json
import time
from typing import Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.public.deps import require_agent
from app.api.public.runtime import execute_agent_run, persist_result, prepare_agent_run
from app.core.errors import ApiException
from app.core.ratelimit import allow_qpm
from app.core.security import new_id, now_iso
from app.db import models as m
from app.db.session import get_db
from app.runtime import get_runtime_engine
from app.runtime.event_store import append_events, load_events
from app.services.system_config import limit as config_limit

chat_router = APIRouter()
agui_router = APIRouter()


class ChatMessageInput(BaseModel):
    role: str
    content: str = ""


class PublicChatRequest(BaseModel):
    messages: list[ChatMessageInput]
    threadId: str | None = None
    runId: str | None = None
    variables: dict[str, str] = Field(default_factory=dict)
    stream: bool = False
    includeUsage: bool = True


class AGUIRunRequest(BaseModel):
    threadId: str
    runId: str
    messages: list[ChatMessageInput] = Field(default_factory=list)
    tools: list[dict[str, Any]] = Field(default_factory=list)
    context: dict[str, Any] = Field(default_factory=dict)
    state: dict[str, Any] = Field(default_factory=dict)
    forwardedProps: dict[str, Any] = Field(default_factory=dict)


def last_user_content(messages: list[ChatMessageInput]) -> str:
    for message in reversed(messages):
        if message.role == "user" and message.content.strip():
            return message.content
    raise ApiException("invalid_request", "messages 至少需要一条非空 user 内容", 400)


def sse(event: str, payload: dict[str, Any]) -> str:
    return f"event: {event}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"


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


async def chat_stream(db: AsyncSession, agent: m.Agent, runtime_request: Any, snapshot: dict):
    """真增量 SSE：逐 token 消费引擎事件，不等待整轮运行完成。"""
    message_id = ""
    ended = False
    pending_error: dict[str, Any] | None = None
    try:
        async for engine_event in get_runtime_engine().run_stream(runtime_request):
            kind = engine_event.get("type")
            if kind == "run_started":
                message_id = new_id("msg-")
                yield sse("message.start", {"type": "message.start", "messageId": message_id, "role": "assistant"})
            elif kind == "message_start":
                if not message_id:
                    message_id = engine_event["messageId"]
                    yield sse("message.start", {"type": "message.start", "messageId": message_id, "role": "assistant"})
            elif kind == "token":
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
                if kind == "run_error":
                    pending_error = {
                        "type": "message.error",
                        "messageId": message_id,
                        "code": engine_event.get("code", "agent_run_failed"),
                        "message": engine_event.get("message", "运行失败"),
                    }
    except Exception as exc:  # noqa: BLE001
        pending_error = {"type": "message.error", "messageId": message_id, "code": "agent_run_failed", "message": str(exc)[:200]}
    finally:
        # 无论成功、运行错误还是异常，SSE 都必须以 message.end 收尾，避免客户端挂起。
        if pending_error is not None:
            yield sse("message.error", pending_error)
        if not ended or pending_error is not None:
            yield sse("message.end", {"type": "message.end", "messageId": message_id or new_id("msg-")})


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

    try:
        async for engine_event in get_runtime_engine().run_stream(runtime_request):
            if engine_event["type"] in ("run_finished", "run_error"):
                result = engine_event.get("result")
                if result is not None:
                    await persist_result(db, agent, result, snapshot, "agui")
            for payload in to_agui_events(engine_event):
                event = await emit(payload)
                yield sse(event["type"], event)
    except Exception as exc:  # noqa: BLE001
        event = await emit({"type": "RUN_ERROR", "code": "agent_run_failed", "message": str(exc)[:200]})
        yield sse("RUN_ERROR", event)
    finally:
        if pending:
            await append_events(runtime_request.run_id, pending)
            pending.clear()


async def replay_agui(events: list[dict[str, Any]], last_event_id: int):
    for event in events:
        if int(event.get("sequence") or 0) <= last_event_id:
            continue
        yield sse(str(event.get("type") or "MESSAGE"), event)


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
