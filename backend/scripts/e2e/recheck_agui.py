"""针对上游偶发超时的 AGUI 补测：放宽 QA Agent 运行时长后重跑 AGUI 全套用例。"""
import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import agui_api  # noqa: E402
from common import DEMO_KEY, Recorder, new_client  # noqa: E402


async def main() -> int:
    agent_id = sys.argv[1]
    rec = Recorder()
    async with new_client() as admin:
        await admin.post("/api/v1/auth/login", json={"key": DEMO_KEY})
        await admin.patch(f"/api/v1/agents/{agent_id}", json={
            "advanced": {"keepContext": True, "autoRetry": True, "outputThinkingSummary": False,
                         "maxToolCalls": 10, "maxRunSeconds": 300},
        })
        reset = await admin.post(f"/api/v1/agents/{agent_id}/credential/reset")
        print("credential reset", reset.status_code)
        token = reset.json()["fullKey"]
    rec.state["agent_id"] = agent_id
    rec.state["agent_token"] = token
    await agui_api.run(rec)
    summary = rec.summary()
    print(json.dumps({key: value for key, value in summary.items() if key not in ("cases", "failures", "skipped_cases")}, ensure_ascii=False, indent=2))
    for case in summary["failures"]:
        print("FAIL", case["name"], case["detail"])
    for case in summary["skipped_cases"]:
        print("SKIP", case["name"], case["detail"])
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
