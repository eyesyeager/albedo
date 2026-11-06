"""AGUI 对外发布接口全方位测试：SSE 序列、sequence、断点回放、鉴权、协议与限流。"""
from __future__ import annotations

import asyncio
import uuid

from common import DEMO_KEY, Recorder, new_client, qa, sse_data, sse_events


async def run(rec: Recorder) -> None:
    agent_id = rec.state.get("agent_id", "")
    token = rec.state.get("agent_token", "")
    if not agent_id or not token:
        rec.skip("agui", "AGUI 全部用例", "管理面未成功创建 QA Agent 或未签发 Token")
        return

    headers = {"Authorization": f"Bearer {token}"}
    path = f"/api/agui/v1/agents/{agent_id}/run"
    run_id = qa("run-agui")

    async with new_client() as client:
        await rec.call(client, "agui", "AGUI 无 Token 拒绝", "POST", path, expected=401,
                       json={"threadId": "QA-thd", "runId": "QA-run", "messages": [{"id": "m1", "role": "user", "content": "hi"}]})
        await rec.call(client, "agui", "AGUI 错误 Token 拒绝", "POST", path, expected=401,
                       headers={"Authorization": "Bearer alb_agt_QA_invalid_0000"},
                       json={"threadId": "QA-thd", "runId": "QA-run", "messages": [{"id": "m1", "role": "user", "content": "hi"}]})
        await rec.call(client, "agui", "AGUI 缺少 user 消息拒绝", "POST", path, expected=400, headers=headers,
                       json={"threadId": "QA-thd", "runId": "QA-run", "messages": []})

        async def agui_attempt(attempt_run_id: str) -> tuple[int, str, str]:
            async with client.stream("POST", path, headers={**headers, "X-AGUI-Version": "1"}, timeout=300, json={
                "threadId": qa("thd-agui"), "runId": attempt_run_id,
                "messages": [{"id": "msg-qa-1", "role": "user", "content": "请用三句话说明 AGUI 事件流测试"}],
                "context": {"variables": {}},
            }) as response:
                streamed = "".join([chunk async for chunk in response.aiter_text()])
                return response.status_code, response.headers.get("x-agui-version", ""), streamed

        body = ""
        status = 0
        version = ""
        successful_run_id = run_id
        last_error = ""
        # 真实上游偶发超时（agent_run_timeout）时重试一次，错误码保留在明细中便于归因
        for attempt in range(2):
            attempt_run_id = run_id if attempt == 0 else f"{run_id}-r{attempt + 1}"
            try:
                status, version, body = await agui_attempt(attempt_run_id)
                errors = [item for item in sse_data(body) if item.get("type") == "RUN_ERROR"]
                last_error = f"{errors[-1].get('code')}: {errors[-1].get('message')}"[:160] if errors else ""
                successful_run_id = attempt_run_id
                if not errors and "RUN_FINISHED" in sse_events(body):
                    break
            except Exception as exc:  # noqa: BLE001
                last_error = f"EXC {type(exc).__name__}: {str(exc)[:140]}"

        events = sse_events(body)
        data = sse_data(body)
        sequences = [int(item["sequence"]) for item in data if "sequence" in item]
        deltas = [item for item in data if item.get("type") == "TEXT_MESSAGE_CONTENT"]
        rec.note("agui", "AGUI 事件流（真实模型）", status == 200 and bool(events) and events[0] == "RUN_STARTED",
                 f"HTTP {status} events={len(events)} first={events[:1]} last={events[-1:]} error={last_error}")
        rec.note("agui", "X-AGUI-Version 响应头", version == "1", f"header={version}")
        rec.note("agui", "RUN_FINISHED / RUN_ERROR 终止事件", bool(events) and events[-1] in ("RUN_FINISHED", "RUN_ERROR"), f"last={events[-1:]}")
        rec.note("agui", "sequence 单调递增且连续", bool(sequences) and sequences == list(range(1, len(sequences) + 1)), f"count={len(sequences)}")
        rec.note("agui", "TEXT_MESSAGE 事件完整", "TEXT_MESSAGE_START" in events and "TEXT_MESSAGE_END" in events,
                 f"types={sorted(set(events))} error={last_error}")
        rec.note("agui", "逐 token 增量（非完成后分块）", len(deltas) >= 2,
                 f"TEXT_MESSAGE_CONTENT={len(deltas)} error={last_error}")

        # 断点回放：携带 Last-Event-ID 只返回缓存中更晚的 sequence
        try:
            async with client.stream("POST", path, headers={**headers, "Last-Event-ID": "2"}, timeout=60, json={
                "threadId": qa("thd-agui-resume"), "runId": successful_run_id, "messages": [],
            }) as resumed:
                resumed_body = "".join([chunk async for chunk in resumed.aiter_text()])
                resumed_status = resumed.status_code
            resumed_sequences = [int(item["sequence"]) for item in sse_data(resumed_body) if "sequence" in item]
            rec.note("agui", "Last-Event-ID 断点回放", resumed_status == 200 and (not resumed_sequences or resumed_sequences[0] > 2),
                     f"HTTP {resumed_status} firstSequence={resumed_sequences[:1]}")
        except Exception as exc:  # noqa: BLE001
            rec.note("agui", "Last-Event-ID 断点回放", False, f"EXC {type(exc).__name__}: {str(exc)[:160]}")

        # 协议开关：未开启 AGUI 的 QA Agent 必须 409
        unpublished_id = rec.state.get("unpublished_agent_id", "")
        other_token = rec.state.get("unpublished_token", "")
        if unpublished_id and not other_token:
            async with new_client() as admin:
                await admin.post("/api/v1/auth/login", json={"key": DEMO_KEY}, timeout=30)
                reset = await admin.post(f"/api/v1/agents/{unpublished_id}/credential/reset", timeout=30)
                if reset.status_code == 200:
                    other_token = reset.json().get("fullKey", "")
        if unpublished_id and other_token:
            await rec.call(client, "agui", "AGUI 协议未开启拒绝", "POST", f"/api/agui/v1/agents/{unpublished_id}/run", expected=409,
                           headers={"Authorization": f"Bearer {other_token}"},
                           json={"threadId": "QA-thd", "runId": qa("run-disabled"), "messages": [{"id": "m1", "role": "user", "content": "hi"}]})
        else:
            rec.skip("agui", "AGUI 协议未开启拒绝", "未取得未发布 Agent 的 Token")

        # QPM 限流：用不触发模型的无效请求快速打满 60 次固定窗口，放在最后避免影响其它用例
        async def probe() -> int:
            try:
                response = await client.post(f"/v1/agents/{agent_id}/chat", headers=headers, timeout=30,
                                            json={"messages": []})
                return response.status_code
            except Exception:  # noqa: BLE001
                return -1

        batches = await asyncio.gather(*[asyncio.gather(*[probe() for _ in range(20)]) for _ in range(4)])
        codes = [code for batch in batches for code in batch]
        rec.note("agui", "QPM 限流触发 429", 429 in codes, f"codes={sorted(set(codes))} total={len(codes)}")
