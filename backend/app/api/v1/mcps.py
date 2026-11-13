import json
import time

from fastapi import APIRouter, Depends
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.adapters.gateway import McpGateway
from app.api.deps import current_session
from app.core.errors import ApiException
from app.core.network import assert_public_url
from app.core.security import new_id, now_iso, suffix_of
from app.runtime.mcp_loader import validate_stdio_command
from app.db import models as m
from app.db.seed import to_row
from app.db.session import get_db
from app.schemas.domain import McpServer, McpToolTestResult, ToolDiscoveryResult
from app.schemas.requests import (
    EnabledPatch,
    McpDraft,
    McpPatch,
    McpTestRequest,
    McpToolTestRequest,
    UsagePatch,
)
from app.schemas.responses import WriteResponse
from app.services.audit import make_log_entry, write_log
from app.services.system_config import get_configs
from app.services.common import (
    commit_write,
    get_or_404,
    mcp_to_dict,
    read_headers,
    store_credential,
    tool_schema_dict,
    validate_blank,
    validate_json_object,
    validate_name,
    write_headers,
)
from app.services.tool_usage import record_tool_call

router = APIRouter(tags=["mcps"])


@router.get("/mcps", response_model=list[McpServer], summary="MCP 列表")
async def list_mcps(
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> list[dict]:
    rows = (await db.scalars(select(m.McpServer))).all()
    return [await mcp_to_dict(db, row) for row in rows]


@router.post("/mcps/test", response_model=ToolDiscoveryResult, summary="MCP 连接测试与工具发现（真实连接）")
async def test_mcp(
    payload: McpTestRequest,
    _: m.Session = Depends(current_session),
) -> dict:
    started = time.perf_counter()
    tools = await McpGateway().discover_tools(payload.model_dump())
    return {
        "toolCount": len(tools),
        "tools": tools,
        "latencyMs": int((time.perf_counter() - started) * 1000),
    }


def stored_secret(value: str | None) -> dict:
    """读取解密后的 JSON 对象；历史脏数据按空对象处理，不阻断测试。"""
    text = read_headers(value)
    if not (text or "").strip():
        return {}
    try:
        parsed = json.loads(text)
    except (json.JSONDecodeError, TypeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def stored_headers(row: m.McpServer) -> dict:
    return stored_secret(row.headers_enc)


def server_draft(row: m.McpServer) -> dict:
    """运行期调用所需的完整服务配置（含配额、并发、超时与 stdio 环境变量）。"""
    return {
        "id": row.id,
        "transport": row.transport,
        "endpoint": row.endpoint,
        "headers": stored_headers(row),
        "env": stored_secret(row.env_enc),
        "timeoutSeconds": row.timeout_seconds,
        "qpm": row.qpm,
        "maxConcurrency": row.max_concurrency,
    }


def discover_draft(row: m.McpServer) -> dict:
    return {
        "transport": row.transport,
        "endpoint": row.endpoint,
        "headers": json.dumps(stored_headers(row), ensure_ascii=False),
        "env": json.dumps(stored_secret(row.env_enc), ensure_ascii=False),
        "timeoutSeconds": row.timeout_seconds,
    }


async def sync_tools(db: AsyncSession, row: m.McpServer, discovered: list[dict]) -> tuple[int, int]:
    """同步工具清单：新增默认启用、保留已有工具的启停状态；不再暴露的工具保留记录并置为停用。"""
    existing = {
        tool.key: tool
        for tool in (await db.scalars(select(m.McpTool).where(m.McpTool.server_id == row.id))).all()
    }
    added = offline = 0
    seen: set[str] = set()
    for item in discovered:
        key = str(item["key"])
        seen.add(key)
        tool = existing.get(key)
        if tool is None:
            db.add(m.McpTool(
                id=new_id("mt-"),
                server_id=row.id,
                name=str(item["name"]),
                key=key,
                description=str(item.get("description") or ""),
                input_schema=json.dumps(item.get("inputSchema") or {}, ensure_ascii=False),
                enabled=True,
            ))
            added += 1
            continue
        tool.name = str(item["name"])
        tool.description = str(item.get("description") or "")
        tool.input_schema = json.dumps(item.get("inputSchema") or {}, ensure_ascii=False)
    for key, tool in existing.items():
        if key not in seen and tool.enabled:
            # 服务端不再暴露该工具：保留记录避免破坏已挂载引用，仅停用。
            tool.enabled = False
            offline += 1
    await db.flush()
    return added, offline


@router.post("/mcps/{mcp_id}/test", response_model=WriteResponse, summary="测试已接入 MCP 的连接")
async def test_existing_mcp(
    mcp_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.McpServer, mcp_id)
    stamp = now_iso()
    started = time.perf_counter()
    try:
        tools = await McpGateway().discover_tools(discover_draft(row))
    except ApiException as exc:
        row.connection = "error"
        row.last_tested_at = stamp
        row.updated_at = stamp
        await write_log(db, make_log_entry(
            "测试 MCP 连接",
            f"「{row.name}」连接测试失败：{exc.message}",
            resource_id=mcp_id,
            status="failed",
        ))
        await db.commit()
        raise

    latency_ms = int((time.perf_counter() - started) * 1000)
    added, offline = await sync_tools(db, row, tools)
    row.connection = "normal"
    row.last_tested_at = stamp
    row.updated_at = stamp
    entity = await mcp_to_dict(db, row)
    changes = []
    if added:
        changes.append(f"新增 {added} 个")
    if offline:
        changes.append(f"下线 {offline} 个")
    summary = f"「{row.name}」连接测试通过，发现 {len(tools)} 个工具，延迟 {latency_ms} ms"
    if changes:
        summary += f"（{'、'.join(changes)}）"
    return await commit_write(
        db, entity, "测试 MCP 连接", summary,
        resource_id=mcp_id, latencyMs=latency_ms, toolCount=len(tools),
    )


@router.post("/mcps", response_model=WriteResponse, status_code=201, summary="接入 MCP")
async def create_mcp(
    payload: McpDraft,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    name = validate_name(payload.name, "MCP 名称")
    endpoint = validate_blank(payload.endpoint, "服务地址/命令不能为空")
    validate_json_object(payload.headers)
    validate_json_object(payload.env, "stdio 环境变量必须是合法的 JSON 对象")
    if str(getattr(payload, "transport", "") or "streamable_http") != "stdio":
        await assert_public_url(endpoint)
    config = await get_configs(db)
    defaults = config["defaults"]["mcp"]
    tools = await McpGateway().discover_tools(payload.model_dump())
    stamp = now_iso()
    row = m.McpServer(
        id=new_id("mcp-"),
        name=name,
        description=payload.description.strip() or defaults["description"],
        transport=payload.transport,
        endpoint=endpoint,
        headers_enc=write_headers(payload.headers),
        env_enc=write_headers(payload.env),
        timeout_seconds=payload.timeoutSeconds,
        qpm=int(defaults["qpm"]),
        max_concurrency=int(defaults["maxConcurrency"]),
        enabled=True,
        connection="normal",
        call_count=0,
        success_rate=0.0,
        last_tested_at=stamp,
        created_at=stamp,
        updated_at=stamp,
    )
    db.add(row)
    for tool in tools:
        db.add(m.McpTool(
            id=new_id("mt-"),
            server_id=row.id,
            name=tool["name"],
            key=tool["key"],
            description=tool["description"],
            input_schema=json.dumps(tool.get("inputSchema") or {}, ensure_ascii=False),
            enabled=True,
        ))
    await store_credential(db, "mcp_auth_header", row.id, payload.headers or "{}", suffix_of(payload.headers or "{}"))
    await db.flush()
    entity = await mcp_to_dict(db, row)
    return await commit_write(db, entity, "接入 MCP 服务器", f"接入「{name}」，发现 {len(tools)} 个工具", resource_id=row.id)


@router.get("/mcps/{mcp_id}", response_model=McpServer, summary="MCP 详情")
async def get_mcp(
    mcp_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    return await mcp_to_dict(db, await get_or_404(db, m.McpServer, mcp_id))


@router.get("/mcps/{mcp_id}/calls", summary="MCP 调用记录")
async def mcp_calls(
    mcp_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    await get_or_404(db, m.McpServer, mcp_id)
    rows = (
        await db.scalars(select(m.McpCallRecord).where(m.McpCallRecord.server_id == mcp_id).order_by(m.McpCallRecord.timestamp.desc()))
    ).all()
    items = [
        {
            "id": str(row.id),
            "toolKey": row.tool_key,
            "runId": row.run_id,
            "status": row.status,
            "durationMs": row.duration_ms,
            "timestamp": row.timestamp,
        }
        for row in rows
    ]
    return {"items": items, "total": len(items)}


@router.patch("/mcps/{mcp_id}", response_model=WriteResponse, summary="保存 MCP 配置")
async def update_mcp(
    mcp_id: str,
    payload: McpPatch,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.McpServer, mcp_id)
    patch = payload.model_dump(exclude_unset=True)
    if "name" in patch:
        validate_blank(patch["name"], "名称与服务地址不能为空")
    if "endpoint" in patch:
        validate_blank(patch["endpoint"], "名称与服务地址不能为空")
    # transport 可以单独从 HTTP 改成 stdio，因此按「最终生效值」校验，而不是只看本次 patch。
    final_transport = str(patch.get("transport") or row.transport or "")
    final_endpoint = str(patch.get("endpoint") if patch.get("endpoint") is not None else row.endpoint or "")
    final_env = patch.get("env") if "env" in patch else read_headers(row.env_enc)
    if final_transport == "stdio":
        validate_stdio_command(final_endpoint, final_env)
    elif "endpoint" in patch:
        await assert_public_url(final_endpoint)
    if "headers" in patch:
        validate_json_object(patch["headers"])
    if "env" in patch:
        validate_json_object(patch["env"], "stdio 环境变量必须是合法的 JSON 对象")
    updates = to_row(patch, m.McpServer, aliases={"headers": "headers_enc", "env": "env_enc"})
    # 常规接口只下发空串；留空表示「不修改」，避免覆盖已加密的请求头与环境变量。
    if "headers" in patch:
        if str(patch["headers"]).strip():
            updates["headers_enc"] = write_headers(patch["headers"])
        else:
            updates.pop("headers_enc", None)
    if "env" in patch:
        if str(patch["env"]).strip():
            updates["env_enc"] = write_headers(patch["env"])
        else:
            updates.pop("env_enc", None)
    for column, value in updates.items():
        setattr(row, column, value)
    row.updated_at = now_iso()
    await db.flush()
    entity = await mcp_to_dict(db, row)
    return await commit_write(db, entity, "保存 MCP 配置", f"更新 MCP {mcp_id} 的配置", resource_id=mcp_id)


@router.delete("/mcps/{mcp_id}", response_model=WriteResponse, summary="删除 MCP")
async def remove_mcp(
    mcp_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.McpServer, mcp_id)
    name = row.name
    tool_count = int(
        await db.scalar(select(func.count()).select_from(m.McpTool).where(m.McpTool.server_id == mcp_id)) or 0
    )
    call_count = int(
        await db.scalar(select(func.count()).select_from(m.McpCallRecord).where(m.McpCallRecord.server_id == mcp_id)) or 0
    )
    mounts = (
        await db.scalars(select(m.AgentMount).where(m.AgentMount.kind == "mcp", m.AgentMount.resource_id == mcp_id))
    ).all()
    await db.execute(delete(m.McpTool).where(m.McpTool.server_id == mcp_id))
    await db.execute(delete(m.McpCallRecord).where(m.McpCallRecord.server_id == mcp_id))
    await db.execute(
        delete(m.AgentMount).where(m.AgentMount.kind == "mcp", m.AgentMount.resource_id == mcp_id)
    )
    await db.execute(
        delete(m.Credential).where(
            m.Credential.type == "mcp_auth_header",
            m.Credential.resource_id == mcp_id,
        )
    )
    await db.delete(row)
    return await commit_write(
        db, None, "删除 MCP",
        f"删除「{name}」，清理 {tool_count} 个工具与 {call_count} 条调用记录，解除 {len(mounts)} 个智能体挂载",
        resource_id=mcp_id,
    )


@router.patch("/mcps/{mcp_id}/enabled", response_model=WriteResponse, summary="启停 MCP")
async def toggle_mcp(
    mcp_id: str,
    payload: EnabledPatch,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.McpServer, mcp_id)
    row.enabled = payload.enabled
    row.updated_at = now_iso()
    await db.flush()
    entity = await mcp_to_dict(db, row)
    return await commit_write(
        db, entity, "启用 MCP" if payload.enabled else "停用 MCP",
        f"MCP {mcp_id} 已{'启用' if payload.enabled else '停用'}", resource_id=mcp_id,
    )


@router.patch("/mcps/{mcp_id}/tools/{tool_id}", response_model=WriteResponse, summary="启停 MCP 工具")
async def toggle_mcp_tool(
    mcp_id: str,
    tool_id: str,
    payload: EnabledPatch,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    tool = await db.scalar(select(m.McpTool).where(m.McpTool.id == tool_id, m.McpTool.server_id == mcp_id))
    if tool is None:
        from app.core.errors import ApiException

        raise ApiException("resource_not_found", "未找到该 MCP 工具", 404)
    tool.enabled = payload.enabled
    await db.flush()
    entity = await mcp_to_dict(db, await get_or_404(db, m.McpServer, mcp_id))
    await db.commit()
    return {"entity": entity, "log": None}


@router.post("/mcps/{mcp_id}/tools/{tool_id}/test", response_model=McpToolTestResult, summary="MCP 工具调用测试")
async def test_mcp_tool(
    mcp_id: str,
    tool_id: str,
    payload: McpToolTestRequest,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.McpServer, mcp_id)
    tool = await db.scalar(
        select(m.McpTool).where(m.McpTool.id == tool_id, m.McpTool.server_id == mcp_id)
    )
    if tool is None:
        raise ApiException("resource_not_found", "未找到该 MCP 工具", 404)

    timeout = max(5.0, min(120.0, float(payload.timeoutSeconds or row.timeout_seconds or 30)))
    outcome = await McpGateway().call_tool(
        server_draft(row),
        tool_key=tool.key,
        arguments=payload.arguments,
        timeout=timeout,
    )
    stamp = now_iso()
    status = str(outcome["status"])
    duration_ms = int(outcome["durationMs"])
    output = str(outcome.get("output") or "")
    error = outcome.get("error")

    await record_tool_call(
        db,
        kind="mcp",
        resource_id=row.id,
        tool_key=tool.key,
        status=status,
        duration_ms=duration_ms,
        run_id="test",
    )
    extra: dict = {"durationMs": duration_ms}
    if status == "success":
        extra["outputSummary"] = output[:300]
    else:
        extra["errorCode"] = "upstream_unavailable"
        extra["errorMessage"] = str(error or "")[:300]
    entry = await write_log(db, make_log_entry(
        f"{row.name} · {tool.key}",
        f"工具调用测试{'成功' if status == 'success' else '失败'}：{output[:120] if status == 'success' else str(error or '')[:120]}",
        resource_id=row.id,
        status=status,
        log_type="mcp",
        source="system",
        **extra,
    ))
    await db.commit()
    return {
        "toolKey": tool.key,
        "status": status,
        "durationMs": duration_ms,
        "output": output,
        "error": error,
        "timestamp": stamp,
        "log": entry,
    }


@router.post("/mcps/{mcp_id}/tools/{tool_id}/schema", summary="重新读取 MCP 工具入参定义")
async def refresh_mcp_tool_schema(
    mcp_id: str,
    tool_id: str,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.McpServer, mcp_id)
    tool = await db.scalar(
        select(m.McpTool).where(m.McpTool.id == tool_id, m.McpTool.server_id == mcp_id)
    )
    if tool is None:
        raise ApiException("resource_not_found", "未找到该 MCP 工具", 404)

    tools = await McpGateway().discover_tools({
        "transport": row.transport,
        "endpoint": row.endpoint,
        "headers": json.dumps(stored_headers(row), ensure_ascii=False),
        "timeoutSeconds": row.timeout_seconds,
    })
    await sync_tools(db, row, tools)
    row.updated_at = now_iso()
    tool_key = tool.key
    await db.commit()
    refreshed = await db.scalar(
        select(m.McpTool).where(m.McpTool.server_id == mcp_id, m.McpTool.key == tool_key)
    )
    return {
        "toolKey": tool_key,
        "inputSchema": tool_schema_dict(refreshed.input_schema if refreshed else None),
    }


@router.patch("/mcps/{mcp_id}/usage", response_model=WriteResponse, summary="更新 MCP 调用额度")
async def update_mcp_usage(
    mcp_id: str,
    payload: UsagePatch,
    db: AsyncSession = Depends(get_db),
    _: m.Session = Depends(current_session),
) -> dict:
    row = await get_or_404(db, m.McpServer, mcp_id)
    row.qpm = payload.qpm
    row.max_concurrency = payload.maxConcurrency
    row.updated_at = now_iso()
    await db.flush()
    entity = await mcp_to_dict(db, row)
    return await commit_write(
        db, entity, "更新 MCP 使用配置",
        f"调用额度 {payload.qpm}/分钟，最大并发 {payload.maxConcurrency}", resource_id=mcp_id,
    )
