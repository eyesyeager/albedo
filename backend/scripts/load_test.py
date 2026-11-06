import asyncio
import statistics
import sys
import time
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.config import get_settings  # noqa: E402

BASE = "http://127.0.0.1:8000"
CONCURRENCY = 20
ROUNDS = 5
PATHS = ("/api/v1/logs?page=1&pageSize=20", "/api/v1/workspace/bootstrap")


async def main() -> None:
    settings = get_settings()
    async with httpx.AsyncClient(base_url=BASE, timeout=30) as client:
        login = await client.post("/api/v1/auth/login", json={"key": settings.demo_access_key})
        if login.status_code != 200:
            raise SystemExit(f"login failed: {login.status_code}")

        async def call(path: str) -> tuple[int, float]:
            started = time.perf_counter()
            response = await client.get(path)
            return response.status_code, (time.perf_counter() - started) * 1000

        results: list[tuple[int, float]] = []
        for _ in range(ROUNDS):
            for path in PATHS:
                results.extend(await asyncio.gather(*[call(path) for _ in range(CONCURRENCY)]))

    latencies = sorted(ms for _, ms in results)
    errors = [status for status, _ in results if status >= 400]
    p50 = statistics.median(latencies)
    p95 = latencies[min(len(latencies) - 1, int(len(latencies) * 0.95))]
    print(
        f"requests={len(results)} errors={len(errors)} "
        f"p50={p50:.1f}ms p95={p95:.1f}ms max={max(latencies):.1f}ms"
    )


if __name__ == "__main__":
    asyncio.run(main())
