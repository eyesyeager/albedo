"""在线全量测试编排入口。

用法：cd backend && PYTHONPATH=. uv run python scripts/e2e/run_all.py
顺序：管理面 → 对外面 → AGUI；破坏性用例仅覆盖鉴权/错误路径。
"""
from __future__ import annotations

import asyncio
import json
import sys
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import admin_api  # noqa: E402
import agui_api  # noqa: E402
import public_api  # noqa: E402
from common import Recorder  # noqa: E402


async def main() -> int:
    rec = Recorder()
    for group, module in (("admin", admin_api), ("public", public_api), ("agui", agui_api)):
        try:
            await module.run(rec)
        except Exception as exc:  # noqa: BLE001
            rec.note(group, "模块级异常", False, f"{type(exc).__name__}: {str(exc)[:200]}")
            traceback.print_exc()

    summary = rec.summary()
    target = rec.save()
    print(json.dumps({key: value for key, value in summary.items() if key != "cases"}, ensure_ascii=False, indent=2))
    print(f"\n逐用例明细: {target}")
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
