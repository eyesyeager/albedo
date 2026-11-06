"""管理面 /api/v1/** 全端点在线测试（含成功、鉴权、边界、错误码、并发）。"""
from __future__ import annotations

import asyncio
import io
import json
import uuid
import zipfile
from typing import Any

import httpx

from common import LLM_API_KEY, LLM_BASE_URL, LLM_MODEL, QA, Recorder, login, new_client, qa


def skill_zip() -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("manifest.json", json.dumps({"name": "QA Skill", "version": "v1.0.0", "entry": "readme.md"}))
        archive.writestr("readme.md", "QA online test skill")
    return buffer.getvalue()


async def run(rec: Recorder) -> None:
    async with new_client() as client, new_client() as anon:
        async def hit(name: str, method: str, path: str, expected=200, **kw) -> httpx.Response | None:
            return await rec.call(client, "admin", name, method, path, expected=expected, **kw)

        await rec.call(anon, "admin", "健康检查（无需鉴权）", "GET", "/health")
        await rec.call(anon, "admin", "元数据（无需鉴权）", "GET", "/api/v1/meta")
        for name, path in (
            ("未登录 bootstrap 拒绝", "/api/v1/workspace/bootstrap"),
            ("未登录 agents 拒绝", "/api/v1/agents"),
            ("未登录 logs 拒绝", "/api/v1/logs"),
            ("未登录 stats 拒绝", "/api/v1/stats/overview"),
            ("未登录 settings 拒绝", "/api/v1/settings"),
        ):
            await rec.call(anon, "admin", name, "GET", path, expected=401)
        await rec.call(anon, "admin", "错误访问 Key 拒绝", "POST", "/api/v1/auth/login", expected=401, json={"key": "QA-invalid-key"})

        if not await login(rec, client):
            rec.skip("admin", "管理面全部业务用例", "登录失败，后续用例无法执行")
            return
        await hit("查询会话", "GET", "/api/v1/auth/session")
        boot = await hit("全量工作区快照", "GET", "/api/v1/workspace/bootstrap")
        bootstrap = boot.json() if boot and boot.status_code == 200 else {}
        rec.note("admin", "bootstrap 结构完整", all(key in bootstrap for key in ("agents", "models", "mcps", "logs", "settings")), f"keys={list(bootstrap)[:8]}")

        # ---- 模型 ----
        await hit("模型列表", "GET", "/api/v1/models")
        base_draft = {
            "name": qa("model"), "upstreamModelId": LLM_MODEL or "hy4-preview",
            "type": "chat", "vendor": "QA", "baseUrl": LLM_BASE_URL or "https://api.example.com/v1",
            "apiKey": LLM_API_KEY or "QA-invalid-upstream-key", "headers": "{}",
            "timeoutSeconds": 60, "note": "QA 在线全量测试", "enabled": True, "tested": True,
        }
        live_test = await hit("模型连接测试（真实上游）", "POST", "/api/v1/models/test", expected=(200,), json=base_draft)
        if live_test and live_test.status_code == 200:
            rec.note("admin", "连接测试延迟为实测值", 0 <= live_test.json().get("latencyMs", -1) < 180000, f"latencyMs={live_test.json().get('latencyMs')}")
        await hit("模型连接测试 SSRF 拦截", "POST", "/api/v1/models/test", expected=(400, 422, 502), json={**base_draft, "baseUrl": "http://169.254.169.254/latest/meta-data"})
        await hit("模型连接测试缺 Key", "POST", "/api/v1/models/test", expected=(400, 401), json={**base_draft, "apiKey": ""})
        created = await hit("接入模型", "POST", "/api/v1/models", expected=201, json=base_draft)
        model_id = ""
        if created and created.status_code == 201:
            entity = created.json()["entity"]
            model_id = entity["id"]
            rec.state["model_id"] = model_id
            rec.note("admin", "新模型不再写入伪造延迟", entity.get("latencyMs") == 0, f"latencyMs={entity.get('latencyMs')}")
            await hit("模型详情", "GET", f"/api/v1/models/{model_id}")
            await hit("保存模型配置", "PATCH", f"/api/v1/models/{model_id}", json={"note": "QA 已修改", "timeoutSeconds": 45})
            await hit("停用模型", "PATCH", f"/api/v1/models/{model_id}/enabled", json={"enabled": False})
            await hit("启用模型", "PATCH", f"/api/v1/models/{model_id}/enabled", json={"enabled": True})
            await hit("复测已接入模型（真实上游）", "POST", f"/api/v1/models/{model_id}/test", expected=(200,))
            await hit("模型不存在返回 404", "GET", "/api/v1/models/QA-not-exist", expected=404)
        spare = await hit("接入待删除模型", "POST", "/api/v1/models", expected=201, json={**base_draft, "name": qa("model-todelete")})
        if spare and spare.status_code == 201:
            await hit("删除模型", "DELETE", f"/api/v1/models/{spare.json()['entity']['id']}")

        # ---- 发布版本 ----
        publication_id = ""
        call_identifier = qa("call")
        if model_id:
            pub = await hit("发布版本列表", "GET", f"/api/v1/models/{model_id}/publications")
            rec.note("admin", "发布版本列表初始为空", isinstance(pub.json(), list) if pub and pub.status_code == 200 else False, "")
            created_pub = await hit("发布模型版本", "POST", f"/api/v1/models/{model_id}/publications", expected=201, json={
                "version": "v1.0.0", "callIdentifier": call_identifier,
                "apiKey": f"alb_sk_qa_{uuid.uuid4().hex[:16]}", "headers": "",
                "temperature": 0.7, "maxOutputTokens": 1024, "qpm": 60,
            })
            if created_pub and created_pub.status_code == 201:
                created_body = created_pub.json()
                # 发布接口把实体放在 publication 字段并返回一次性的 fullKey
                publication_id = (created_body.get("publication") or {}).get("id", "")
                rec.state["publication_id"] = publication_id
                rec.state["call_identifier"] = call_identifier
                rec.state["publication_token"] = created_body.get("fullKey", "")
                await hit("修改发布版本", "PATCH", f"/api/v1/publications/{publication_id}", json={"enabled": True, "qpm": 30})
                rotated = await hit("轮换发布版本密钥", "POST", f"/api/v1/publications/{publication_id}/key/rotate", json={"apiKey": f"alb_sk_qa_{uuid.uuid4().hex[:16]}"})
                if rotated and rotated.status_code == 200:
                    rec.state["publication_token"] = rotated.json().get("fullKey", "")

        # ---- MCP ----
        await hit("MCP 列表", "GET", "/api/v1/mcps")
        mcp_draft = {
            "name": qa("mcp"), "description": "QA 在线全量测试", "transport": "streamable_http",
            "endpoint": "https://mcp.deepwiki.com/mcp", "headers": "{}", "timeoutSeconds": 60, "tested": True,
        }
        discovery = await hit("MCP 连接测试（真实工具发现）", "POST", "/api/v1/mcps/test", expected=(200, 502), json=mcp_draft)
        if discovery and discovery.status_code == 200:
            tools = discovery.json().get("tools") or []
            rec.note("admin", "工具发现返回真实工具", bool(tools) and not any(t.get("simulated") for t in tools), f"toolCount={len(tools)}")
        await hit("MCP 连接测试非法地址", "POST", "/api/v1/mcps/test", expected=(400, 422, 502), json={**mcp_draft, "endpoint": "http://127.0.0.1:9999/mcp"})
        mcp_created = await hit("接入 MCP", "POST", "/api/v1/mcps", expected=(201, 502), json=mcp_draft)
        mcp_id = ""
        if mcp_created and mcp_created.status_code == 201:
            entity = mcp_created.json()["entity"]
            mcp_id = entity["id"]
            rec.state["mcp_id"] = mcp_id
            rec.note("admin", "新建 MCP 成功率未知而非 100%", entity.get("successRate") in (0, 0.0, None), f"successRate={entity.get('successRate')}")
            await hit("MCP 详情", "GET", f"/api/v1/mcps/{mcp_id}")
            await hit("MCP 调用记录", "GET", f"/api/v1/mcps/{mcp_id}/calls")
            await hit("保存 MCP 配置", "PATCH", f"/api/v1/mcps/{mcp_id}", json={"description": "QA 已修改"})
            await hit("停用 MCP", "PATCH", f"/api/v1/mcps/{mcp_id}/enabled", json={"enabled": False})
            await hit("启用 MCP", "PATCH", f"/api/v1/mcps/{mcp_id}/enabled", json={"enabled": True})
            await hit("更新 MCP 调用额度", "PATCH", f"/api/v1/mcps/{mcp_id}/usage", json={"qpm": 120, "maxConcurrency": 8})
            tools = entity.get("tools") or []
            if tools:
                await hit("启停 MCP 工具", "PATCH", f"/api/v1/mcps/{mcp_id}/tools/{tools[0]['id']}", json={"enabled": False})
        await hit("MCP 不存在返回 404", "GET", "/api/v1/mcps/QA-not-exist", expected=404)

        # ---- Skill ----
        await hit("Skill 列表", "GET", "/api/v1/skills")
        package = skill_zip()
        form = {"name": qa("skill"), "instructions": "功能：QA 测试。使用步骤：挂载后调用。", "fileName": "qa-skill.zip", "sizeBytes": str(len(package))}
        files = {"file": ("qa-skill.zip", package, "application/zip")}
        skill = await hit("上传 Skill", "POST", "/api/v1/skills", expected=201, data=form, files=files)
        skill_id = ""
        if skill and skill.status_code == 201:
            skill_id = skill.json()["entity"]["id"]
            rec.state["skill_id"] = skill_id
            await hit("上传 Skill 新版本", "POST", f"/api/v1/skills/{skill_id}/versions", expected=201, data=form, files=files)
            detail = await hit("Skill 详情", "GET", f"/api/v1/skills/{skill_id}")
            if detail and detail.status_code == 200:
                versions = detail.json().get("versions") or []
                # skill_to_dict 不向前端暴露 manifest；此处校验真实上传产生的版本记录与校验状态
                rec.note("admin", "Skill 版本真实落库且包校验通过", len(versions) == 2 and detail.json().get("verified") is True,
                         f"versions={len(versions)} verified={detail.json().get('verified')}")
        await hit("Skill 上传非 ZIP 拒绝", "POST", "/api/v1/skills", expected=(400, 422), data={**form, "fileName": "qa.txt"}, files={"file": ("qa.txt", b"not a zip", "text/plain")})

        # ---- 内置工具与影响清单 ----
        tool_list = await hit("内置工具列表", "GET", "/api/v1/tools")
        tool_id = ""
        if tool_list and tool_list.status_code == 200 and tool_list.json():
            tool_id = tool_list.json()[0]["id"]
            await hit("停用内置工具", "PATCH", f"/api/v1/tools/{tool_id}/enabled", json={"enabled": False})
            await hit("启用内置工具", "PATCH", f"/api/v1/tools/{tool_id}/enabled", json={"enabled": True})
        for kind, resource in (("model", model_id), ("mcp", mcp_id), ("skill", skill_id), ("tool", tool_id)):
            if resource:
                await hit(f"影响清单 {kind}", "GET", f"/api/v1/resources/{kind}/{resource}/impact")

        # ---- Agent ----
        await hit("Agent 列表", "GET", "/api/v1/agents")
        agent_draft = {
            "name": qa("agent"), "description": "QA 在线全量测试智能体",
            "defaultModelId": model_id or (bootstrap.get("models") or [{}])[0].get("id", ""),
            "systemPrompt": "你是 QA 测试助手，请用一句话回答问题。",
        }
        agent = await hit("创建 Agent", "POST", "/api/v1/agents", expected=201, json=agent_draft)
        agent_id = ""
        if agent and agent.status_code == 201:
            entity = agent.json()["entity"]
            agent_id = entity["id"]
            rec.state["agent_id"] = agent_id
            rec.state["agent_version"] = entity.get("version")
            await hit("Agent 详情", "GET", f"/api/v1/agents/{agent_id}")
            # 真实上游偶发超过默认 120s，仅对 QA 测试 Agent 放宽运行时长
            await hit("放宽 QA Agent 运行时长", "PATCH", f"/api/v1/agents/{agent_id}", json={
                "advanced": {"keepContext": True, "autoRetry": True, "outputThinkingSummary": False,
                             "maxToolCalls": 10, "maxRunSeconds": 300},
            })
            await hit("发布前检查", "POST", f"/api/v1/agents/{agent_id}/publish-check")
            await hit("开启标准 API", "PATCH", f"/api/v1/agents/{agent_id}/protocols", json={"protocol": "api", "enabled": True})
            await hit("开启 AGUI", "PATCH", f"/api/v1/agents/{agent_id}/protocols", json={"protocol": "agui", "enabled": True})
            token_response = await hit("重置 Agent Token", "POST", f"/api/v1/agents/{agent_id}/credential/reset")
            if token_response and token_response.status_code == 200:
                rec.state["agent_token"] = token_response.json().get("fullKey", "")
            await hit("对话历史", "GET", f"/api/v1/agents/{agent_id}/history")
            await hit("测试会话运行（真实模型）", "POST", f"/api/v1/agents/{agent_id}/test-runs", expected=(200, 500, 504), json={"content": "请用一句话说明本次测试目的"})
            await hit("清理测试会话", "DELETE", f"/api/v1/agents/{agent_id}/test-session", expected=(200, 204))
            current = await hit("读取 Agent 最新版本号", "GET", f"/api/v1/agents/{agent_id}")
            version = current.json().get("version") if current and current.status_code == 200 else None
            if version is not None:
                responses = await asyncio.gather(*[
                    client.patch(f"/api/v1/agents/{agent_id}", json={"name": qa("agent-concurrent"), "expectedVersion": version}, timeout=30)
                    for _ in range(2)
                ])
                statuses = sorted(response.status_code for response in responses)
                rec.note("admin", "并发保存乐观锁", 200 in statuses and 409 in statuses, f"statuses={statuses}")
        unpublished = await hit("创建未发布 Agent", "POST", "/api/v1/agents", expected=201, json={**agent_draft, "name": qa("agent-unpublished")})
        if unpublished and unpublished.status_code == 201:
            rec.state["unpublished_agent_id"] = unpublished.json()["entity"]["id"]
        spare_agent = await hit("创建待删除 Agent", "POST", "/api/v1/agents", expected=201, json={**agent_draft, "name": qa("agent-todelete")})
        if spare_agent and spare_agent.status_code == 201:
            await hit("删除 Agent", "DELETE", f"/api/v1/agents/{spare_agent.json()['entity']['id']}")
        await hit("Agent 不存在返回 404", "GET", "/api/v1/agents/QA-not-exist", expected=404)

        # ---- 日志 ----
        logs = await hit("日志列表", "GET", "/api/v1/logs", params={"page": 1, "pageSize": 5, "range": "1y"})
        log_id = ""
        if logs and logs.status_code == 200 and logs.json().get("items"):
            log_id = logs.json()["items"][0]["logId"]
        for label, params in (
            ("日志类型筛选", {"type": "session", "range": "1y"}),
            ("日志状态筛选", {"status": "success", "range": "1y"}),
            ("日志关键词筛选", {"keyword": QA, "range": "1y"}),
            ("日志分页", {"page": 2, "pageSize": 1, "range": "1y"}),
            ("日志时间范围", {"range": "30d"}),
        ):
            await hit(label, "GET", "/api/v1/logs", params=params)
        if log_id:
            await hit("日志详情与链路", "GET", f"/api/v1/logs/{log_id}")
        await hit("日志不存在返回 404", "GET", "/api/v1/logs/QA-not-exist", expected=404)
        export = await hit("创建导出任务", "POST", "/api/v1/logs/exports", expected=202, json={
            "format": "csv", "filters": {"range": "1y"}, "filtersLabel": "QA 在线测试导出",
            "logIds": [log_id] if log_id else [],
        })
        if export and export.status_code == 202:
            task_id = export.json()["id"]
            polled = await hit("轮询导出任务", "GET", f"/api/v1/logs/exports/{task_id}")
            if polled and polled.status_code == 200:
                url = polled.json().get("downloadUrl")
                rec.note("admin", "导出任务返回签名链接", bool(url), "仅校验存在性，不记录签名内容")
                if url:
                    await hit("签名链接下载", "GET", url)
                await hit("未签名下载拒绝", "GET", f"/api/v1/logs/exports/{task_id}/download", expected=403)
        await hit("JSON 格式导出", "POST", "/api/v1/logs/exports", expected=202, json={
            "format": "json", "filters": {"range": "1y"}, "filtersLabel": "QA JSON", "logIds": [],
        })

        # ---- 统计 / 搜索 / 通知 / 设置 ----
        overview = await hit("概览统计", "GET", "/api/v1/stats/overview")
        if overview and overview.status_code == 200:
            rec.note("admin", "概览包含真实耗时变化字段", "avgDurationChange" in overview.json(), "")
        await hit("14 天趋势", "GET", "/api/v1/stats/trends", params={"days": 14})
        await hit("30 天趋势", "GET", "/api/v1/stats/trends", params={"days": 30})
        await hit("全局搜索 QA", "GET", "/api/v1/search", params={"q": QA})
        await hit("全局搜索空结果", "GET", "/api/v1/search", params={"q": "QA-no-such-resource-xyz"})
        await hit("通知列表", "GET", "/api/v1/notifications")
        await hit("全部标记已读", "POST", "/api/v1/notifications/read-all", expected=(200, 204))
        settings = await hit("读取设置", "GET", "/api/v1/settings")
        original_name = settings.json().get("workspaceName", "") if settings and settings.status_code == 200 else ""
        await hit("保存常规设置", "PATCH", "/api/v1/settings", json={"workspaceName": qa("workspace")})
        await hit("保存日志保留策略", "PATCH", "/api/v1/settings", json={"retentionDays": 90})
        if original_name:
            await hit("恢复工作区名称", "PATCH", "/api/v1/settings", json={"workspaceName": original_name})
        await hit("非法保留天数拒绝", "PATCH", "/api/v1/settings", expected=(400, 422), json={"retentionDays": "not-a-number"})

        # ---- 破坏性用例：按约束仅覆盖错误路径，不改动真实凭据 / 不清空业务数据 ----
        await hit("修改访问 Key 错误当前值拒绝", "POST", "/api/v1/settings/access-key", expected=(400, 401, 409, 422), json={
            "current": "QA-wrong-current-key", "next": "QA-new-key-1234567890", "confirm": "QA-new-key-1234567890",
        })
        await rec.call(anon, "admin", "未登录读取系统配置拒绝", "GET", "/api/v1/settings/configs", expected=401)
        rec.skip("admin", "系统配置修改（正向）", "按用户约束不改动线上系统配置，故仅覆盖鉴权路径")
        rec.skip("admin", "修改访问 Key（正向）", "按用户约束不改动真实登录凭据，故仅覆盖错误路径")

        # ---- 退出登录放最后 ----
        await hit("退出登录", "POST", "/api/v1/auth/logout", expected=(200, 204))
        session_after_logout = await hit("退出后会话失效", "GET", "/api/v1/auth/session")
        rec.note("admin", "退出后 authenticated=false", bool(session_after_logout and session_after_logout.json().get("authenticated") is False),
                 "session 端点为公开查询，200 + authenticated=false 表示会话已撤销")
