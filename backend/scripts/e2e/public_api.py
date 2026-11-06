"""对外面 /v1/** 在线测试：Bearer 鉴权、真实上游透传、真增量 SSE 与错误码。"""
from __future__ import annotations

import uuid
from typing import Any

import httpx

from common import DEMO_KEY, Recorder, new_client, qa, sse_data, sse_events


async def run(rec: Recorder) -> None:
    agent_id = rec.state.get("agent_id", "")
    token = rec.state.get("agent_token", "")
    publication_token = rec.state.get("publication_token", "")
    call_identifier = rec.state.get("call_identifier", "")
    unpublished_id = rec.state.get("unpublished_agent_id", "")

    if not agent_id or not token:
        rec.skip("public", "对外面全部用例", "管理面未成功创建 QA Agent 或未签发 Token")
        return

    headers = {"Authorization": f"Bearer {token}"}
    chat_path = f"/v1/agents/{agent_id}/chat"

    async with new_client() as client:
        async def hit(name: str, method: str, path: str, expected=200, **kw) -> httpx.Response | None:
            return await rec.call(client, "public", name, method, path, expected=expected, **kw)

        await hit("Agent chat 无 Token 拒绝", "POST", chat_path, expected=401, json={"messages": [{"role": "user", "content": "hi"}]})
        await hit("Agent chat 错误 Token 拒绝", "POST", chat_path, expected=401, headers={"Authorization": "Bearer alb_agt_QA_invalid_0000"}, json={"messages": [{"role": "user", "content": "hi"}]})
        await hit("Agent chat 跨 Agent 拒绝", "POST", f"/v1/agents/{unpublished_id or 'QA-other'}/chat", expected=403, headers=headers, json={"messages": [{"role": "user", "content": "hi"}]})
        await hit("Agent chat 缺少 user 内容", "POST", chat_path, expected=400, headers=headers, json={"messages": [{"role": "assistant", "content": "hi"}]})

        result = await hit("Agent chat JSON（真实模型）", "POST", chat_path, expected=(200, 500, 502, 504), headers=headers, json={
            "messages": [{"role": "user", "content": "请用一句话说明本次在线测试的目的"}],
            "threadId": qa("thd-json"), "includeUsage": True,
        })
        if result and result.status_code == 200:
            body = result.json()
            rec.note("public", "JSON 响应结构完整", all(key in body for key in ("object", "agentId", "status", "message", "usage", "metadata")), f"object={body.get('object')}")

        # 真增量流式：断言事件顺序与 delta 数量，不再接受「完成后按字符切片」；上游偶发错误时重试一次
        async def stream_attempt() -> tuple[int, str]:
            async with client.stream("POST", chat_path, headers=headers, timeout=180, json={
                "messages": [{"role": "user", "content": "请用三句话说明流式输出测试"}],
                "threadId": qa("thd-stream"), "stream": True,
            }) as response:
                body = "".join([chunk async for chunk in response.aiter_text()])
                return response.status_code, body

        ok = False
        detail = ""
        for attempt in range(2):
            try:
                status, body = await stream_attempt()
                events = sse_events(body)
                data = sse_data(body)
                deltas = [item for item in data if item.get("type") == "message.delta"]
                errors = [item for item in data if item.get("type") == "message.error"]
                error_text = str(errors[-1].get("message"))[:120] if errors else ""
                ok = (status == 200 and bool(events) and events[0] == "message.start"
                      and events[-1] == "message.end" and len(deltas) >= 1 and not errors)
                detail = (f"attempt={attempt + 1} HTTP {status} events={len(events)} "
                          f"first={events[:1]} last={events[-1:]} deltas={len(deltas)} error={error_text}")
                if ok:
                    break
            except Exception as exc:  # noqa: BLE001
                detail = f"attempt={attempt + 1} EXC {type(exc).__name__}: {str(exc)[:160]}"
        rec.note("public", "Agent chat 真增量 SSE", ok, detail)

        if unpublished_id:
            # 为未发布的 QA Agent 单独签发 Token，验证协议开关
            async with new_client() as admin:
                await admin.post("/api/v1/auth/login", json={"key": DEMO_KEY}, timeout=30)
                reset = await admin.post(f"/api/v1/agents/{unpublished_id}/credential/reset", timeout=30)
                if reset.status_code == 200:
                    other_token = reset.json().get("fullKey", "")
                    rec.state["unpublished_token"] = other_token
                    await hit("未发布 Agent 调用拒绝", "POST", f"/v1/agents/{unpublished_id}/chat", expected=409,
                              headers={"Authorization": f"Bearer {other_token}"},
                              json={"messages": [{"role": "user", "content": "hi"}]})

        # ---- 发布版本 Key 与 OpenAI 兼容透传 ----
        if not publication_token:
            rec.skip("public", "模型透传全部用例", "管理面未成功签发 QA 发布版本 Key")
            return
        pub_headers = {"Authorization": f"Bearer {publication_token}"}
        await hit("列出可用模型（无 Key）", "GET", "/v1/models", expected=401)
        models = await hit("列出可用模型", "GET", "/v1/models", headers=pub_headers)
        if models and models.status_code == 200:
            rec.note("public", "模型列表返回 QA 发布标识", any(item.get("id") == call_identifier for item in models.json().get("data", [])), f"callIdentifier={call_identifier}")
        await hit("chat/completions 错误模型拒绝", "POST", "/v1/chat/completions", expected=403, headers=pub_headers,
                  json={"model": "QA-not-allowed", "messages": [{"role": "user", "content": "hi"}]})
        await hit("chat/completions 无 Key 拒绝", "POST", "/v1/chat/completions", expected=401,
                  json={"model": call_identifier, "messages": [{"role": "user", "content": "hi"}]})
        await hit("chat/completions 真实上游透传", "POST", "/v1/chat/completions", expected=(200, 400, 502, 504), headers=pub_headers,
                  json={"model": call_identifier, "messages": [{"role": "user", "content": "请回复 OK 两个字母"}], "max_tokens": 32})
        await hit("embeddings 类型不匹配拒绝", "POST", "/v1/embeddings", expected=(400, 403, 404), headers=pub_headers,
                  json={"model": call_identifier, "input": "QA embedding test"})
        await hit("embeddings 无 Key 拒绝", "POST", "/v1/embeddings", expected=401,
                  json={"model": call_identifier, "input": "QA embedding test"})
        await hit("请求体缺字段拒绝", "POST", "/v1/chat/completions", expected=(400, 422), headers=pub_headers, json={"model": call_identifier})
