"""Albedo 在线全量测试公共夹具。

凭据只从环境变量 / backend/.env 读取，绝不写入报告与日志；
所有测试资源使用 QA- 前缀标记，且不做清理（遵循测试约束）。
"""
from __future__ import annotations

import json
import os
import time
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import httpx

BACKEND_ROOT = Path(__file__).resolve().parents[2]
BASE_URL = os.getenv("ALBEDO_BASE_URL", "http://localhost:8000").rstrip("/")
QA = "QA-"
TIMEOUT = float(os.getenv("ALBEDO_E2E_TIMEOUT", "180"))


def read_env_value(key: str, default: str = "") -> str:
    value = os.getenv(key)
    if value:
        return value
    env_file = BACKEND_ROOT / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            if line.startswith(f"{key}="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    return default


DEMO_KEY = read_env_value("DEMO_ACCESS_KEY")
LLM_BASE_URL = read_env_value("LLM_BASE_URL")
LLM_API_KEY = read_env_value("LLM_API_KEY")
LLM_MODEL = read_env_value("LLM_MODEL")


def qa(label: str) -> str:
    return f"{QA}{label}-{uuid.uuid4().hex[:6]}"


def suffix(value: str | None) -> str:
    return (value or "")[-4:]


@dataclass
class Case:
    group: str
    name: str
    ok: bool
    detail: str
    elapsed_ms: int
    skipped: bool = False


class Recorder:
    def __init__(self) -> None:
        self.cases: list[Case] = []
        self.state: dict[str, Any] = {}

    def note(self, group: str, name: str, ok: bool, detail: str = "") -> bool:
        self.cases.append(Case(group, name, bool(ok), detail[:300], 0))
        return bool(ok)

    def skip(self, group: str, name: str, detail: str) -> None:
        self.cases.append(Case(group, name, False, detail[:300], 0, skipped=True))

    async def call(
        self,
        client: httpx.AsyncClient,
        group: str,
        name: str,
        method: str,
        path: str,
        *,
        expected: int | tuple[int, ...] = 200,
        **kwargs: Any,
    ) -> httpx.Response | None:
        started = time.perf_counter()
        codes = expected if isinstance(expected, (tuple, list, set)) else (expected,)
        try:
            kwargs.setdefault("timeout", TIMEOUT)
            response = await client.request(method, path, **kwargs)
            elapsed = int((time.perf_counter() - started) * 1000)
            ok = response.status_code in codes
            detail = f"HTTP {response.status_code}"
            if not ok:
                detail += f" body={response.text[:180].replace(chr(10), ' ')}"
            self.cases.append(Case(group, name, ok, detail, elapsed))
            return response
        except Exception as exc:  # noqa: BLE001
            elapsed = int((time.perf_counter() - started) * 1000)
            self.cases.append(Case(group, name, False, f"EXC {type(exc).__name__}: {str(exc)[:180]}", elapsed))
            return None

    def summary(self) -> dict[str, Any]:
        active = [case for case in self.cases if not case.skipped]
        skipped = [case for case in self.cases if case.skipped]
        failed = [case for case in active if not case.ok]
        groups: dict[str, dict[str, int]] = {}
        for case in active:
            bucket = groups.setdefault(case.group, {"pass": 0, "fail": 0})
            bucket["pass" if case.ok else "fail"] += 1
        return {
            "total": len(active),
            "passed": len(active) - len(failed),
            "failed": len(failed),
            "skipped": len(skipped),
            "groups": groups,
            "failures": [asdict(case) for case in failed],
            "skipped_cases": [asdict(case) for case in skipped],
            "cases": [asdict(case) for case in self.cases],
        }

    def save(self) -> Path:
        target = BACKEND_ROOT / "var" / "e2e-results.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(self.summary(), ensure_ascii=False, indent=2), encoding="utf-8")
        return target


def new_client() -> httpx.AsyncClient:
    return httpx.AsyncClient(base_url=BASE_URL, timeout=TIMEOUT, follow_redirects=True)


async def login(rec: Recorder, client: httpx.AsyncClient) -> bool:
    response = await rec.call(
        client, "auth", "管理面登录", "POST", "/api/v1/auth/login",
        json={"key": DEMO_KEY},
    )
    return bool(response and response.status_code == 200)


def sse_events(body: str) -> list[str]:
    return [line[7:] for line in body.splitlines() if line.startswith("event: ")]


def sse_data(body: str) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for line in body.splitlines():
        if line.startswith("data: "):
            try:
                result.append(json.loads(line[6:]))
            except json.JSONDecodeError:
                continue
    return result
