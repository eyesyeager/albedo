import secrets
import string
from collections.abc import AsyncIterator
from typing import Any

from fastapi import APIRouter, Depends, Header, Request, Response
from fastapi.responses import StreamingResponse
from sqlalchemy import delete, func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_session
from app.api.public.agents import sse
from app.api.public.runtime import persist_failure, persist_failure_logs, persist_result
from app.core.errors import ApiException
from app.core.security import new_id, now_iso, suffix_of
from app.db import models as m
from app.db.seed import to_row
from app.db.session import get_db
from app.runtime import RuntimeRequest, get_runtime_engine
from app.runtime.live_model import map_upstream_error
from app.schemas.domain import Agent as AgentSchema
from app.schemas.domain import HistoryResult, PublishCheckResult, SendResult
from app.schemas.requests import AgentDraft, AgentPatch, ProtocolPatch, TestRunRequest
from app.schemas.responses import CredentialView, WriteResponse
from app.services.agent_rules import extract_variables, publish_issues, replace_mounts, validate_variables
from app.services.common import agent_to_dict, commit_write, get_or_404, reveal_credential, store_credential, validate_name
from app.services.runtime_snapshot import build_runtime_snapshot, safe_snapshot
from app.services.snapshot import row_dict
from app.services.system_config import get_configs

router = APIRouter(tags=["agents"])


def random_suffix() -> str:
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(4))


def _assert_publishable(issues: list[str]) -> None:
    if issues:
        raise ApiException(
            "publish_check_failed",
            f"发布前检查未通过：{issues[0]}",
            409,
            issues=issues,
        )


@router.get("/agents", response_model=list[AgentSchema], summary="智能体列表")
async def list_agents(
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> list[dict]:
    rows = (await db.scalars(select(m.Agent))).all()
    return [await agent_to_dict(db, row) for row in rows]


@router.post("/agents", response_model=WriteResponse, status_code=201, summary="创建智能体")
async def create_agent(
    payload: AgentDraft,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    name = validate_name(payload.name, "智能体名称")
    model = await db.get(m.ModelResource, payload.defaultModelId)
    if model is None or not model.enabled or model.type != "chat" or model.connection != "normal":
        raise ApiException("validation_error", "请选择已启用且连接正常的对话模型", 400)
    count = await db.scalar(select(func.count()).select_from(m.Agent)) or 0
    config = await get_configs(db)
    defaults = config["defaults"]["agent"]
    themes = [item["value"] for item in config["options"]["agentThemes"]] or [defaults["theme"]]
    stamp = now_iso()
    agent_id = new_id("agt-")
    row = m.Agent(
        id=agent_id,
        name=name,
        description=payload.description.strip() or defaults["description"],
        theme=themes[count % len(themes)],
        default_model_id=model.id,
        test_model_id=model.id,
        system_prompt=payload.systemPrompt.strip() or defaults["systemPrompt"].replace("{name}", name),
        variables=[],
        model_params=dict(defaults["modelParams"]),
        advanced=dict(defaults["advanced"]),
        api_enabled=False,
        agui_enabled=False,
        credential_suffix=random_suffix(),
        session_count=0,
        version=1,
        created_at=stamp,
        updated_at=stamp,
    )
    db.add(row)
    db.add(m.AgentMount(agent_id=agent_id, kind="model", resource_id=model.id))
    await store_credential(db, "agent_token", agent_id, f"alb_agt_{secrets.token_urlsafe(24)}", row.credential_suffix)
    await db.flush()
    entity = await agent_to_dict(db, row)
    return await commit_write(db, entity, "创建智能体", f"创建「{name}」，默认模型 {model.name}", resource_id=agent_id, version=1, updatedAt=stamp)


@router.get("/agents/{agent_id}", response_model=AgentSchema, summary="智能体详情")
async def get_agent(
    agent_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    return await agent_to_dict(db, await get_or_404(db, m.Agent, agent_id))


@router.get("/agents/{agent_id}/credential", response_model=CredentialView, summary="查看智能体访问凭证")
async def reveal_agent_credential(
    agent_id: str,
    response: Response,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    await get_or_404(db, m.Agent, agent_id)
    response.headers["Cache-Control"] = "no-store"
    return await reveal_credential(db, "agent_token", agent_id, "智能体访问凭证")


@router.patch("/agents/{agent_id}", response_model=WriteResponse, summary="保存智能体配置（乐观锁）")
async def update_agent(
    agent_id: str,
    payload: AgentPatch,
    if_match: str | None = Header(default=None, alias="If-Match"),
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.Agent, agent_id)
    patch = payload.model_dump(exclude_unset=True)
    expected = patch.pop("expectedVersion", None)
    if expected is None and if_match and if_match.isdigit():
        expected = int(if_match)
    if expected is not None and expected != row.version:
        raise ApiException("stale_version", "配置已被其他标签页更新，请刷新后比较再保存", 409)

    if "name" in patch:
        patch["name"] = validate_name(patch["name"], "智能体名称")
    if "systemPrompt" in patch and not str(patch["systemPrompt"]).strip():
        raise ApiException("validation_error", "System Prompt 保存前不可为空", 400)
    if "systemPrompt" in patch:
        extract_variables(str(patch["systemPrompt"]))
    if "variables" in patch and isinstance(patch["variables"], list):
        validate_variables(patch["variables"])
    mounts = patch.pop("mounts", None)
    if "modelParams" in patch and isinstance(patch["modelParams"], dict):
        params = patch["modelParams"]
        temperature = params.get("temperature", 0.7)
        if not isinstance(temperature, (int, float)) or not 0 <= temperature <= 2:
            raise ApiException("validation_error", "温度需在 0 ~ 2 之间", 400)

    updates = to_row(patch, m.Agent)
    updates.pop("version", None)
    if mounts is not None:
        await replace_mounts(db, agent_id, mounts)
    # 无论调用方是否传 expectedVersion，写库都带 WHERE version 的 CAS；
    # 未提供时用本次读取到的版本，避免并发保存静默互相覆盖。
    baseline = expected if expected is not None else row.version
    values = {**updates, "version": m.Agent.version + 1, "updated_at": now_iso()}
    result = await db.execute(
        update(m.Agent)
        .where(m.Agent.id == agent_id, m.Agent.version == baseline)
        .values(**values)
    )
    if result.rowcount == 0:
        await db.rollback()
        raise ApiException("stale_version", "配置已被其他标签页更新，请刷新后比较再保存", 409)
    await db.flush()
    await db.refresh(row)
    entity = await agent_to_dict(db, row)
    return await commit_write(
        db, entity, "保存智能体配置", f"更新智能体 {agent_id} 的配置",
        resource_id=agent_id, version=row.version, updatedAt=row.updated_at,
    )


@router.delete("/agents/{agent_id}", response_model=WriteResponse, summary="删除智能体")
async def remove_agent(
    agent_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.Agent, agent_id)
    name = row.name
    await db.execute(delete(m.AgentMount).where(m.AgentMount.agent_id == agent_id))
    await db.execute(delete(m.ChatMessage).where(m.ChatMessage.agent_id == agent_id))
    await db.execute(delete(m.Credential).where(m.Credential.type == "agent_token", m.Credential.resource_id == agent_id))
    await db.delete(row)
    return await commit_write(db, None, "删除智能体", f"删除「{name}」", resource_id=agent_id)


@router.post("/agents/{agent_id}/publish-check", response_model=PublishCheckResult, summary="发布前检查")
async def check_publish(
    agent_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.Agent, agent_id)
    issues = await publish_issues(db, row)
    return {"passed": not issues, "issues": issues}


@router.patch("/agents/{agent_id}/protocols", response_model=WriteResponse, summary="开启/关闭发布协议")
async def set_protocol(
    agent_id: str,
    payload: ProtocolPatch,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.Agent, agent_id)
    if payload.enabled:
        _assert_publishable(await publish_issues(db, row))
    baseline = row.version
    result = await db.execute(
        update(m.Agent)
        .where(m.Agent.id == agent_id, m.Agent.version == baseline)
        .values(**{
            "api_enabled" if payload.protocol == "api" else "agui_enabled": payload.enabled,
            "version": m.Agent.version + 1,
            "updated_at": now_iso(),
        })
    )
    if result.rowcount == 0:
        await db.rollback()
        raise ApiException("stale_version", "配置已被其他标签页更新，请刷新后重试", 409)
    await db.refresh(row)
    entity = await agent_to_dict(db, row)
    label = "标准对话 API" if payload.protocol == "api" else "AGUI 协议接口"
    return await commit_write(
        db, entity,
        "开启智能体发布接口" if payload.enabled else "关闭智能体发布接口",
        f"{row.name} 的{label}已{'开启' if payload.enabled else '关闭'}", resource_id=agent_id,
    )


@router.post("/agents/{agent_id}/credential/reset", response_model=WriteResponse, summary="重置 Agent 访问凭证")
async def reset_credential(
    agent_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.Agent, agent_id)
    full_key = f"alb_agt_{secrets.token_urlsafe(24)}"
    suffix = suffix_of(full_key)
    baseline = row.version
    result = await db.execute(
        update(m.Agent)
        .where(m.Agent.id == agent_id, m.Agent.version == baseline)
        .values(credential_suffix=suffix, version=m.Agent.version + 1, updated_at=now_iso())
    )
    if result.rowcount == 0:
        await db.rollback()
        raise ApiException("stale_version", "配置已被其他标签页更新，请刷新后重试", 409)
    await store_credential(db, "agent_token", agent_id, full_key, suffix)
    await db.refresh(row)
    return await commit_write(
        db, await agent_to_dict(db, row), "重置 Agent 访问凭证",
        f"智能体 {agent_id} 的旧密钥立即失效，新后缀 {suffix}",
        resource_id=agent_id, fullKey=full_key, credentialSuffix=suffix,
    )


@router.get("/agents/{agent_id}/history", response_model=HistoryResult, summary="对话历史")
async def agent_history(
    agent_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    await get_or_404(db, m.Agent, agent_id)
    rows = (
        await db.scalars(
            select(m.LogEntry).where(m.LogEntry.agent_id == agent_id, m.LogEntry.type == "session").order_by(m.LogEntry.timestamp.desc())
        )
    ).all()
    items = [row_dict(row) for row in rows]
    return {"items": items, "total": len(items), "sources": sorted({row.source for row in rows})}


async def prepare_test_run(
    db: AsyncSession,
    agent_id: str,
    payload: TestRunRequest,
) -> tuple[m.Agent, RuntimeRequest, dict]:
    """校验智能体与测试模型，冻结配置快照并组装运行请求（同步与流式接口共用）。"""
    agent = await get_or_404(db, m.Agent, agent_id)
    model = await db.get(m.ModelResource, payload.testModelId or agent.test_model_id or agent.default_model_id)
    if model is None or not model.enabled:
        raise ApiException("resource_disabled", "当前测试模型已停用或不可用，请先在「模型管理」中启用", 409)
    if model.connection != "normal":
        raise ApiException("connection_test_required", "当前测试模型尚未通过连接测试，请先在「模型管理」中测试连接", 409)

    content = payload.content.strip()
    if not content:
        raise ApiException("validation_error", "消息内容不能为空", 400)

    snapshot = await build_runtime_snapshot(db, agent, model)
    advanced: dict[str, Any] = snapshot.get("advanced") or {}
    run_id = new_id("run_", 8)
    thread_id = payload.threadId.strip() or f"thd_{agent_id}"
    if not advanced.get("keepContext", True):
        thread_id = f"thd_{run_id}"

    request = RuntimeRequest(
        agent_id=agent_id,
        thread_id=thread_id,
        run_id=run_id,
        content=content,
        snapshot=snapshot,
        variables=payload.variables,
        source="test_session",
        strict_variables=False,
    )
    return agent, request, snapshot


async def _persist_stream_run(
    db: AsyncSession,
    agent: m.Agent,
    request: RuntimeRequest,
    snapshot: dict,
    event: dict[str, Any],
) -> dict[str, Any]:
    """流式运行结束后落库，并组装 run.finished 载荷。"""
    result = event.get("result")
    if result is not None:
        await persist_result(db, agent, result, snapshot, "test_session")
        return {
            "runId": result.run_id,
            "threadId": result.thread_id,
            "messages": result.messages,
            "logs": result.logs,
            "finishReason": result.finish_reason,
            "sessionCount": agent.session_count,
        }
    logs = list(event.get("logs") or [])
    await persist_failure_logs(db, agent, request, logs, int(event.get("durationMs") or 0), snapshot)
    return {
        "runId": request.run_id,
        "threadId": request.thread_id,
        "messages": [],
        "logs": logs,
        "finishReason": "failed",
        "sessionCount": agent.session_count,
    }


async def stream_test_run(
    db: AsyncSession,
    agent: m.Agent,
    request: RuntimeRequest,
    snapshot: dict,
) -> AsyncIterator[str]:
    """把引擎增量事件转成管理面 SSE：逐 token 下发，结束后统一落库。"""
    message_id = ""
    message_open = False
    pending_error: dict[str, Any] | None = None
    engine_events = get_runtime_engine().run_stream(request)
    try:
        async for event in engine_events:
            kind = event.get("type")
            if kind == "run_started":
                yield sse("run.started", {
                    "type": "run.started", "runId": request.run_id, "threadId": request.thread_id,
                })
            elif kind == "thinking_delta":
                yield sse("message.thinking", {"type": "message.thinking", "delta": event.get("delta", "")})
            elif kind == "message_start":
                message_id = str(event.get("messageId") or new_id("msg-"))
                message_open = True
                yield sse("message.start", {
                    "type": "message.start", "messageId": message_id, "role": "assistant",
                })
            elif kind == "token":
                message_id = message_id or str(event.get("messageId") or new_id("msg-"))
                yield sse("message.delta", {
                    "type": "message.delta", "messageId": message_id, "delta": event.get("delta", ""),
                })
            elif kind == "message_end":
                yield sse("message.end", {"type": "message.end", "messageId": message_id})
                message_open = False
                message_id = ""
            elif kind == "tool_start":
                yield sse("tool.start", {
                    "type": "tool.start",
                    "toolCallId": event.get("toolCallId", ""),
                    "name": event.get("name", ""),
                    "args": event.get("args") or {},
                })
            elif kind == "tool_result":
                yield sse("tool.result", {
                    "type": "tool.result",
                    "toolCallId": event.get("toolCallId", ""),
                    "status": event.get("status", "success"),
                    "summary": event.get("summary", ""),
                })
            elif kind in ("run_finished", "run_error"):
                payload = await _persist_stream_run(db, agent, request, snapshot, event)
                yield sse("run.finished", {"type": "run.finished", **payload})
                if kind == "run_error":
                    pending_error = {
                        "type": "message.error",
                        "code": event.get("code", "agent_run_failed"),
                        "message": event.get("message", "运行失败"),
                    }
    except Exception as exc:  # noqa: BLE001
        mapped = map_upstream_error(exc)
        pending_error = {
            "type": "message.error",
            "code": getattr(mapped, "code", None) or "agent_run_failed",
            "message": getattr(mapped, "message", None) or str(exc)[:200],
        }
    finally:
        # 客户端断开时及时终止上游运行，避免无人消费的 token 继续写入 checkpoint。
        try:
            await engine_events.aclose()
        except Exception:  # noqa: BLE001
            pass
        # aclose 会注入 GeneratorExit，此时不能再向流里写帧。
        try:
            if message_open:
                yield sse("message.end", {"type": "message.end", "messageId": message_id})
            if pending_error is not None:
                yield sse("message.error", pending_error)
        except (GeneratorExit, RuntimeError):
            pass


@router.post(
    "/agents/{agent_id}/test-runs",
    response_model=SendResult,
    summary="测试会话运行（LangGraph 真实模型与工具，Accept: text/event-stream 时流式）",
)
async def test_run(
    agent_id: str,
    payload: TestRunRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> Any:
    agent, runtime_request, snapshot = await prepare_test_run(db, agent_id, payload)
    if "text/event-stream" in request.headers.get("accept", "").lower():
        return StreamingResponse(
            stream_test_run(db, agent, runtime_request, snapshot),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )
    try:
        result = await get_runtime_engine().run(runtime_request)
    except ApiException as exc:
        # 运行失败也要落审计日志，避免测试会话只看到 500、日志里查不到记录。
        await persist_failure(db, agent, runtime_request, exc, snapshot)
        raise

    for message in result.messages:
        db.add(m.ChatMessage(**{
            "id": message["id"],
            "agent_id": agent_id,
            "thread_id": result.thread_id,
            "run_id": result.run_id,
            "role": message["role"],
            "content": message["content"],
            "tool_call": message.get("toolCall"),
            "timestamp": message["timestamp"],
        }))
    for log in result.logs:
        db.add(m.LogEntry(**to_row(log, m.LogEntry)))
    db.add(m.Run(
        run_id=result.run_id,
        thread_id=result.thread_id,
        agent_id=agent_id,
        source="test_session",
        status="completed" if result.finish_reason != "timeout" else "timeout",
        config_snapshot=safe_snapshot(snapshot),
        finish_reason=result.finish_reason,
        usage=result.usage,
        duration_ms=result.duration_ms,
        created_at=now_iso(),
        updated_at=now_iso(),
    ))
    agent.session_count += 1
    await db.commit()

    if result.finish_reason == "timeout":
        raise ApiException("run_timeout", "本次运行超过最大时长限制，已终止", 504)

    return {
        "messages": result.messages,
        "runId": result.run_id,
        "threadId": result.thread_id,
        "logs": result.logs,
        "sessionCount": agent.session_count,
    }


@router.delete("/agents/{agent_id}/test-session", summary="清空测试会话")
async def clear_test_session(
    agent_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    await get_or_404(db, m.Agent, agent_id)
    # 同一智能体的测试会话可能使用默认 thread，也可能由调用方传入自定义 threadId；
    # 只清理默认 thread 会让自定义会话的 checkpoint 残留，导致「已清空但仍记得」。
    session_threads = set(
        (await db.scalars(
            select(m.Run.thread_id).where(m.Run.agent_id == agent_id, m.Run.source == "test_session")
        )).all()
    )
    session_threads.add(f"thd_{agent_id}")
    # 调试面板按智能体聚合展示消息，因此仍清空该智能体的全部 ChatMessage；
    # checkpoint 只清测试会话相关的 thread，不破坏已发布调用方传入的上下文。
    await db.execute(delete(m.ChatMessage).where(m.ChatMessage.agent_id == agent_id))
    engine = get_runtime_engine()
    cleared = 0
    for thread_id in sorted(session_threads):
        await engine.clear_thread(thread_id, agent_id)
        cleared += 1
    await db.commit()
    return {"ok": True, "clearedThreads": cleared}
