from pathlib import Path

import pytest

from app.core.config import get_settings
from app.runtime.live_model import invoke_with_retry, is_retryable, map_upstream_error
from app.runtime.tools import build_tools, collect_tool_specs


class FakeUpstreamError(Exception):
    def __init__(self, status: int) -> None:
        super().__init__(f"upstream {status}")
        self.status_code = status


def test_upstream_error_mapping() -> None:
    assert map_upstream_error(FakeUpstreamError(401)).code == "upstream_auth_failed"
    assert map_upstream_error(FakeUpstreamError(403)).code == "upstream_auth_failed"
    assert map_upstream_error(FakeUpstreamError(429)).code == "rate_limit_exceeded"
    assert map_upstream_error(FakeUpstreamError(504)).code == "upstream_timeout"
    assert map_upstream_error(FakeUpstreamError(500)).code == "upstream_unavailable"
    assert map_upstream_error(ValueError("bad payload")) is None


def test_retryable_classification() -> None:
    assert is_retryable(FakeUpstreamError(429))
    assert is_retryable(FakeUpstreamError(503))
    assert is_retryable(TimeoutError())
    assert is_retryable(ConnectionError())
    assert not is_retryable(FakeUpstreamError(400))
    assert not is_retryable(FakeUpstreamError(401))


@pytest.mark.asyncio
async def test_retry_eventually_succeeds() -> None:
    class FlakyModel:
        calls = 0

        async def ainvoke(self, messages):
            self.calls += 1
            if self.calls < 3:
                raise FakeUpstreamError(503)
            return "ok"

    model = FlakyModel()
    assert await invoke_with_retry(model, [], max_retries=2, base_delay=0.01) == "ok"
    assert model.calls == 3


@pytest.mark.asyncio
async def test_retry_stops_on_auth_error() -> None:
    class DeniedModel:
        calls = 0

        async def ainvoke(self, messages):
            self.calls += 1
            raise FakeUpstreamError(401)

    with pytest.raises(FakeUpstreamError):
        await invoke_with_retry(DeniedModel(), [], max_retries=2, base_delay=0.01)


@pytest.mark.asyncio
async def test_live_current_time_tool() -> None:
    snapshot = {
        "resolvedMounts": {
            "mcp": [],
            "skill": [],
            "tool": [{"id": "tool-time", "name": "时间获取", "key": "current_time", "description": "返回时间", "enabled": True}],
            "model": [],
        },
    }
    tools, _ = await build_tools(collect_tool_specs(snapshot), snapshot)
    assert len(tools) == 1
    payload = await tools[0].ainvoke({})
    assert "当前时间" in payload
    assert "UTC+8" in payload


@pytest.mark.asyncio
async def test_live_file_parser_rejects_escape() -> None:
    snapshot = {
        "resolvedMounts": {
            "mcp": [],
            "skill": [],
            "tool": [{"id": "tool-file", "name": "文件解析", "key": "file_parser", "description": "解析文件", "enabled": True}],
            "model": [],
        },
    }
    tools, _ = await build_tools(collect_tool_specs(snapshot), snapshot)
    payload = await tools[0].ainvoke({"query": "../../etc/passwd"})
    assert "error" in payload or "阻断" in payload

    settings = get_settings()
    root = Path(settings.upload_dir)
    root.mkdir(parents=True, exist_ok=True)
    sample = root / "m5a2-sample.txt"
    sample.write_text("M5a-2 文件解析验证", encoding="utf-8")
    try:
        parsed = await tools[0].ainvoke({"query": "m5a2-sample.txt"})
        assert "M5a-2 文件解析验证" in parsed
    finally:
        sample.unlink(missing_ok=True)
