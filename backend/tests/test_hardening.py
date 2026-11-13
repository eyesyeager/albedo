"""高杠杆修复项的回归测试（纯函数，不依赖数据库与外网）。"""

import asyncio

import pytest
from pydantic import ValidationError

from app.core.config import is_public_demo_key
from app.core.errors import ApiException
from app.core.network import assert_public_literal
from app.core.security import decrypt_secret, encrypt_secret, rotate_secret
from app.schemas.requests import SettingsPatch
from app.services import common
from app.services.log_retention import clamp_retention_days
from app.services.runtime_snapshot import safe_snapshot


def test_retention_days_bounds():
    assert SettingsPatch(retentionDays=30).retentionDays == 30
    for invalid in (0, -1, 366, 10000):
        with pytest.raises(ValidationError):
            SettingsPatch(retentionDays=invalid)


def test_retention_server_side_clamp():
    assert clamp_retention_days(0) == 1
    assert clamp_retention_days(-30) == 1
    assert clamp_retention_days(9999) == 365


def test_demo_key_normalization():
    assert is_public_demo_key("  ALBEDO-DEMO-2026 ")
    assert is_public_demo_key("Albedo-Demo-2026")
    assert not is_public_demo_key("a-random-strong-key")


def test_ssrf_literal_blocks_private_targets():
    for url in (
        "http://127.0.0.1:8000/health",
        "http://169.254.169.254/latest/meta-data",
        "http://metadata.google.internal/",
        "http://10.0.0.1/",
        "http://[::1]/",
    ):
        with pytest.raises(ApiException):
            assert_public_literal(url)
    assert_public_literal("https://api.example.com/v1/chat")


def test_read_headers_never_returns_ciphertext(monkeypatch):
    monkeypatch.setattr(common, "decrypt_secret", lambda _: None)
    assert common.read_headers("gAAAAA-not-decryptable") == ""


def test_fernet_keyring_roundtrip():
    cipher = encrypt_secret("probe")
    assert decrypt_secret(cipher) == "probe"
    rotated = rotate_secret(cipher)
    assert rotated is not None
    assert decrypt_secret(rotated) == "probe"


def test_safe_snapshot_strips_credentials():
    snapshot = {
        "upstream": {"apiKey": "secret", "baseUrl": "https://example.com"},
        "model": {"headers": "secret", "name": "m"},
        "resolvedMounts": {
            "mcp": [{"headers": "secret", "env": "secret", "name": "s"}],
        },
        "systemPrompt": "hello",
    }
    cleaned = safe_snapshot(snapshot)
    assert "upstream" not in cleaned
    assert cleaned["model"] == {"name": "m"}
    assert cleaned["resolvedMounts"]["mcp"] == [{"name": "s"}]
    assert cleaned["systemPrompt"] == "hello"


def test_system_config_protected_keys_are_not_writable():
    from app.services import system_config

    defaults = {"mcpAllowPrivateUrls": False, "logPageSize": 20}
    with pytest.raises(ApiException):
        system_config._validate_group("limits", {"mcpAllowPrivateUrls": True}, defaults)
    system_config._validate_group("limits", {"logPageSize": 30}, defaults)
    with pytest.raises(ApiException):
        system_config._validate_group("limits", {"logPageSize": "30"}, defaults)


def test_prompt_template_has_no_template_execution():
    from app.runtime.prompts import render_system_prompt

    rendered, missing = render_system_prompt(
        {"systemPrompt": "你好 {{name}}，现在 {{currentTime}}", "variables": ["name"]},
        {"name": "Albedo"},
    )
    assert "你好 Albedo" in rendered
    assert "{{" not in rendered
    assert missing == []

    payload = "{{ cycler.__init__.__globals__.os.popen('id').read() }}"
    with pytest.raises(ApiException):
        render_system_prompt({"systemPrompt": payload, "variables": []}, {})


def test_sandbox_uses_module_allowlist():
    from app.runtime import builtin

    assert builtin.check_code("import math\nprint(math.sqrt(4))") == ""
    assert builtin.check_code("print(sum(range(5)))") == ""
    for payload in (
        "import os\nprint(os.popen('id').read())",
        "import socket",
        "from subprocess import run",
        "eval('1+1')",
        "print(open('/etc/passwd').read())",
        "print(().__class__.__bases__[0].__subclasses__())",
        "import builtins\nprint(builtins.open)",
        "print('{0.__class__}'.format(1))",
    ):
        assert builtin.check_code(payload) != "", payload


def test_stdio_mcp_disabled_by_default():
    from app.runtime.mcp_loader import validate_stdio_command

    with pytest.raises(ApiException):
        validate_stdio_command("python -c 'import os; os.system(\"id\")'")


def test_stdio_command_whitelist_and_env_guard(monkeypatch):
    from types import SimpleNamespace

    from app.runtime import mcp_loader

    monkeypatch.setattr(mcp_loader, "get_settings", lambda: SimpleNamespace(
        mcp_stdio_enabled=True,
        mcp_stdio_allowed_commands="python3",
    ))
    with pytest.raises(ApiException):
        mcp_loader.validate_stdio_command("/bin/sh -c id")
    with pytest.raises(ApiException):
        mcp_loader.validate_stdio_command("python3", {"LD_PRELOAD": "/tmp/evil.so"})
    monkeypatch.setattr(mcp_loader, "get_settings", lambda: SimpleNamespace(
        mcp_stdio_enabled=True,
        mcp_stdio_allowed_commands="",
    ))
    with pytest.raises(ApiException):
        mcp_loader.validate_stdio_command("python3")


def test_backup_excludes_session_credentials():
    import importlib.util
    from pathlib import Path

    path = Path(__file__).resolve().parents[1] / "scripts" / "backup_db.py"
    spec = importlib.util.spec_from_file_location("backup_db_under_test", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert "sessions" in module.EXCLUDED_TABLES
    assert "config_snapshot" in module.DROPPED_COLUMNS["runs"]


def test_csv_cell_blocks_formula_injection():
    from app.api.v1.logs import csv_cell

    assert csv_cell("=1+1") == "\"'=1+1\""
    assert csv_cell("@SUM(A1:A9)") == "\"'@SUM(A1:A9)\""
    assert csv_cell("+cmd") == "\"'+cmd\""
    assert csv_cell("普通文本") == '"普通文本"'
    assert csv_cell('a"b') == '"a""b"'


def test_log_dict_masks_sensitive_fields():
    from app.db import models as m
    from app.services.snapshot import log_dict

    row = m.LogEntry(
        log_id="log-1",
        type="system",
        name="模型调用",
        input_summary="Authorization: Bearer abcdef1234567890",
        output_summary="token=secretvalue123",
        error_message="sk-abcdef1234567890",
    )
    result = log_dict(row)
    assert "abcdef1234567890" not in str(result["inputSummary"])
    assert "secretvalue123" not in str(result["outputSummary"])
    assert "sk-abcdef1234567890" not in str(result["errorMessage"])


def test_rate_key_trusts_forwarded_header_only_when_enabled(monkeypatch):
    from types import SimpleNamespace

    from app.api.v1 import auth

    request = SimpleNamespace(
        client=SimpleNamespace(host="127.0.0.1"),
        headers={"x-forwarded-for": "203.0.113.7, 10.0.0.1"},
    )
    monkeypatch.setattr(auth, "get_settings", lambda: SimpleNamespace(trust_proxy_headers=False))
    assert "127.0.0.1" in auth._rate_key(request)
    monkeypatch.setattr(auth, "get_settings", lambda: SimpleNamespace(trust_proxy_headers=True))
    assert "203.0.113.7" in auth._rate_key(request)


def test_sse_frame_carries_id_and_retry():
    from app.api.public.agents import sse

    frame = sse("TEXT_MESSAGE_CONTENT", {"delta": "hi"}, 7)
    assert frame.startswith("id: 7\nretry: 3000\nevent: TEXT_MESSAGE_CONTENT\n")
    assert '"delta": "hi"' in frame
    assert "id:" not in sse("message.delta", {"delta": "hi"})


def test_timeout_is_not_retried():
    from app.runtime.live_model import is_retryable

    assert not is_retryable(TimeoutError("read timeout"))
    assert not is_retryable(asyncio.TimeoutError())
    assert is_retryable(ConnectionError("connect failed"))


def test_body_limit_check():
    from app.main import body_limit_exceeded

    assert body_limit_exceeded("3000000", 2 * 1024 * 1024)
    assert not body_limit_exceeded("1024", 2 * 1024 * 1024)
    assert not body_limit_exceeded(None, 2 * 1024 * 1024)
    assert not body_limit_exceeded("not-a-number", 2 * 1024 * 1024)


def test_platform_timezone_changes_natural_day():
    from app.core.config import get_settings
    from app.services.trends import local_date, platform_tz

    settings = get_settings()
    original = settings.platform_timezone
    try:
        object.__setattr__(settings, "platform_timezone", "UTC")
        assert local_date("2026-11-10T00:30:00Z") == "2026-11-10"
        object.__setattr__(settings, "platform_timezone", "America/New_York")
        assert local_date("2026-11-10T00:30:00Z") == "2026-11-09"
        object.__setattr__(settings, "platform_timezone", "Not/AZone")
        assert platform_tz().tzname(None) == "UTC"
    finally:
        object.__setattr__(settings, "platform_timezone", original)


async def test_request_id_is_server_generated():
    from httpx import ASGITransport, AsyncClient

    from app.main import app

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/health", headers={"X-Request-Id": "req_attacker"})
    request_id = response.headers.get("x-request-id", "")
    assert request_id.startswith("req_")
    assert request_id != "req_attacker"


def test_cookie_names_follow_secure_setting():
    from app.core.config import get_settings

    settings = get_settings()
    original = settings.cookie_secure
    try:
        object.__setattr__(settings, "cookie_secure", True)
        assert settings.session_cookie_name.startswith("__Host-")
        assert settings.csrf_cookie_name.startswith("__Host-")
        assert settings.session_samesite == "strict"
        object.__setattr__(settings, "cookie_secure", False)
        assert settings.session_cookie_name == "albedo_session"
        assert settings.csrf_cookie_name == "albedo_csrf"
        assert settings.session_samesite == "lax"
    finally:
        object.__setattr__(settings, "cookie_secure", original)


async def test_chat_stream_closes_inner_generator_on_disconnect(monkeypatch):
    from types import SimpleNamespace

    from app.api.public import agents

    closed = False

    async def fake_run_stream(_request):
        nonlocal closed
        try:
            yield {"type": "run_started"}
            await asyncio.sleep(30)
            yield {"type": "token", "messageId": "m1", "delta": "x"}
        finally:
            closed = True

    class FakeEngine:
        def run_stream(self, request):
            return fake_run_stream(request)

    monkeypatch.setattr(agents, "get_runtime_engine", lambda: FakeEngine())
    monkeypatch.setattr(agents, "spawn_interrupted_persist", lambda *args, **kwargs: None)

    request = SimpleNamespace(
        run_id="run-1", thread_id="thd-1", agent_id="agt-1",
        source="standard_api", snapshot={}, content="hi", variables={},
    )
    stream = agents.chat_stream(None, SimpleNamespace(id="agt-1"), request, {})
    assert "keep-alive" in await stream.__anext__()
    assert "message.start" in await stream.__anext__()
    await stream.aclose()
    assert closed
