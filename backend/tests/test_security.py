import asyncio
import io
import time
import zipfile

import pytest
from httpx import AsyncClient

from app.api.v1.logs import build_csv, sign_download_url, verify_download_signature
from app.core.config import get_settings
from app.core.errors import ApiException
from app.core.logging import mask_text, sanitize
from app.core.network import assert_public_url
from app.services.skill_package import inspect_zip

DEMO_KEY = get_settings().demo_access_key


def make_zip(entries: dict[str, str]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return buffer.getvalue()


@pytest.mark.asyncio
async def test_ssrf_blocks_private_targets() -> None:
    for url in ("http://127.0.0.1/admin", "http://169.254.169.254/latest", "http://10.0.0.8/x", "http://localhost/x"):
        with pytest.raises(ApiException):
            await assert_public_url(url)


def test_skill_zip_rejects_unsafe_packages() -> None:
    assert inspect_zip(make_zip({"manifest.json": '{"name":"demo"}', "main.py": "print(1)"}))["files"] == 2

    with pytest.raises(ApiException):
        inspect_zip(make_zip({"../evil.txt": "x"}))
    with pytest.raises(ApiException):
        inspect_zip(make_zip({"manifest.json": "not-json"}))
    with pytest.raises(ApiException):
        inspect_zip(b"not-a-zip")

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        info = zipfile.ZipInfo("link")
        info.external_attr = 0o120777 << 16
        archive.writestr(info, "/etc/passwd")
    with pytest.raises(ApiException):
        inspect_zip(buffer.getvalue())

    many = {f"f{index}.txt": "x" for index in range(501)}
    many["manifest.json"] = '{"name":"demo"}'
    with pytest.raises(ApiException):
        inspect_zip(make_zip(many))

    # 空包（无任何文件）等价于未上传真实内容，必须拒绝
    with pytest.raises(ApiException):
        inspect_zip(make_zip({}))


def test_secret_masking_and_export_redaction() -> None:
    masked = mask_text("Authorization: Bearer sk-live-abcdef api_key=abc123 password: p@ss")
    assert "sk-live-abcdef" not in masked
    assert "abc123" not in masked
    payload = sanitize({"apiKey": "sk-live-abcdef", "accessKey": "alb_secret", "note": "ok"})
    assert payload["apiKey"] != "sk-live-abcdef"
    assert payload["accessKey"] != "alb_secret"
    assert payload["note"] == "ok"

    csv_text = build_csv([{
        "logId": "log-1", "timestamp": "2026-11-05T00:00:00Z", "type": "session", "status": "success",
        "source": "system", "name": "测试", "summary": "Authorization: Bearer sk-live-abcdef", "agentId": "agt-1",
        "runId": "run_1", "durationMs": 10, "errorCode": None,
    }])
    assert "sk-live-abcdef" not in csv_text


def test_signed_download_url() -> None:
    url = sign_download_url("exp-test", 300)
    query = dict(part.split("=", 1) for part in url.split("?", 1)[1].split("&"))
    assert verify_download_signature("exp-test", query["expires"], query["signature"])
    assert not verify_download_signature("exp-other", query["expires"], query["signature"])
    assert not verify_download_signature("exp-test", str(int(time.time()) - 10), query["signature"])
    assert not verify_download_signature("exp-test", None, None)


@pytest.mark.asyncio
async def test_concurrent_agent_update_conflict(client: AsyncClient) -> None:
    login = await client.post("/api/v1/auth/login", json={"key": DEMO_KEY})
    assert login.status_code == 200
    created = await client.post("/api/v1/agents", json={
        "name": "并发测试智能体", "defaultModelId": "mdl-gpt", "systemPrompt": "你是并发测试助手。",
    })
    agent_id = created.json()["entity"]["id"]

    async def rename(name: str):
        return await client.patch(f"/api/v1/agents/{agent_id}", json={"name": name, "expectedVersion": 1})

    first, second = await asyncio.gather(rename("并发 A"), rename("并发 B"))
    assert sorted([first.status_code, second.status_code]) == [200, 409]

    await client.delete(f"/api/v1/agents/{agent_id}")
