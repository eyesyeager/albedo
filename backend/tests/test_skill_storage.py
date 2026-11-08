"""Skill 包存储层测试：COS 权威存储、本地只读缓存、历史包回退与降级。

全部使用注入的假 COS 客户端，不访问真实对象存储。
"""
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.core.config import get_settings
from app.core.errors import ApiException
from app.services.skill_storage import (
    CosSkillStorage,
    LocalSkillStorage,
    get_skill_storage,
    materialize_async,
    normalize_key,
    reset_skill_storage,
)


class _FakeStream:
    def __init__(self, data: bytes) -> None:
        self.data = data

    def read(self) -> bytes:
        return self.data


class _FakeBody:
    def __init__(self, data: bytes) -> None:
        self.data = data

    def get_raw_stream(self) -> _FakeStream:
        return _FakeStream(self.data)


class FakeCosClient:
    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}
        self.get_calls: list[str] = []

    def put_object(self, Bucket: str, Key: str, Body: bytes, **kwargs) -> dict:
        self.objects[Key] = Body
        return {"ETag": "fake"}

    def get_object(self, Bucket: str, Key: str) -> dict:
        self.get_calls.append(Key)
        if Key not in self.objects:
            raise RuntimeError("404 NoSuchKey")
        return {"Body": _FakeBody(self.objects[Key])}

    def delete_object(self, Bucket: str, Key: str) -> dict:
        self.objects.pop(Key, None)
        return {}

    def list_objects(self, Bucket: str, Prefix: str, Marker: str = "", MaxKeys: int = 1000) -> dict:
        keys = sorted(key for key in self.objects if key.startswith(Prefix) and key > Marker)
        return {"Contents": [{"Key": key} for key in keys[:MaxKeys]], "IsTruncated": "false"}


def fake_settings(root: Path) -> SimpleNamespace:
    return SimpleNamespace(
        cos_bucket="albedo-1305182252",
        cos_region="ap-nanjing",
        cos_secret_id="fake-id",
        cos_secret_key="fake-key",
        cos_token="",
        cos_scheme="https",
        cos_endpoint="",
        cos_timeout_seconds=5,
        cos_prefix="skills",
        skill_dir=str(root / "skills"),
        skill_cache_dir=str(root / "cache"),
    )


def test_normalize_key_rejects_traversal() -> None:
    assert normalize_key("/skill-1/v1.0.0/a.zip") == "skill-1/v1.0.0/a.zip"
    assert normalize_key("skill-1\\v1.0.0\\a.zip") == "skill-1/v1.0.0/a.zip"
    for value in ("", "../etc/passwd", "skill-1/../../x", "./x"):
        with pytest.raises(ApiException):
            normalize_key(value)


def test_local_storage_roundtrip(tmp_path: Path) -> None:
    storage = LocalSkillStorage(tmp_path / "skills")
    uri = storage.put("skill-1/v1.0.0/a.zip", b"PK-local")
    assert Path(uri).read_bytes() == b"PK-local"
    path = storage.materialize("skill-1/v1.0.0/a.zip")
    assert path is not None and path.read_bytes() == b"PK-local"
    storage.delete_prefix("skill-1/")
    assert storage.materialize("skill-1/v1.0.0/a.zip") is None


def test_cos_put_uses_bucket_prefix_and_warms_cache(tmp_path: Path) -> None:
    fake = FakeCosClient()
    storage = CosSkillStorage(fake_settings(tmp_path), tmp_path / "cache", client=fake)
    uri = storage.put("skill-1/v1.0.0/demo.zip", b"PK-remote")
    assert uri == "cos://albedo-1305182252/skills/skill-1/v1.0.0/demo.zip"
    assert fake.objects["skills/skill-1/v1.0.0/demo.zip"] == b"PK-remote"
    # 上传后立刻可读，且不回源。
    path = storage.materialize("skill-1/v1.0.0/demo.zip")
    assert path is not None and path.read_bytes() == b"PK-remote"
    assert fake.get_calls == []


def test_cos_materialize_downloads_on_cache_miss(tmp_path: Path) -> None:
    fake = FakeCosClient()
    storage = CosSkillStorage(fake_settings(tmp_path), tmp_path / "cache", client=fake)
    fake.objects["skills/skill-1/v1.0.0/demo.zip"] = b"PK-downloaded"
    path = storage.materialize("skill-1/v1.0.0/demo.zip")
    assert path is not None and path.read_bytes() == b"PK-downloaded"
    assert fake.get_calls == ["skills/skill-1/v1.0.0/demo.zip"]
    # 第二次直接命中缓存。
    assert storage.materialize("skill-1/v1.0.0/demo.zip") == path
    assert len(fake.get_calls) == 1


def test_cos_materialize_falls_back_to_legacy_local_package(tmp_path: Path) -> None:
    fake = FakeCosClient()
    settings = fake_settings(tmp_path)
    storage = CosSkillStorage(settings, tmp_path / "cache", client=fake)
    legacy = tmp_path / "legacy.zip"
    legacy.write_bytes(b"PK-legacy")
    assert storage.materialize("skill-old/v1.0.0/old.zip", str(legacy)) == legacy
    # COS URI 不能当本地路径用。
    assert storage.materialize("skill-old/v1.0.0/old.zip", "cos://bucket/key") is None
    # key 在 skill_dir 下的历史包也能回退。
    fallback = Path(settings.skill_dir) / "skill-old" / "v1.0.0" / "old.zip"
    fallback.parent.mkdir(parents=True, exist_ok=True)
    fallback.write_bytes(b"PK-fallback")
    assert storage.materialize("skill-old/v1.0.0/old.zip") == fallback


def test_cos_delete_prefix_removes_only_that_skill(tmp_path: Path) -> None:
    fake = FakeCosClient()
    storage = CosSkillStorage(fake_settings(tmp_path), tmp_path / "cache", client=fake)
    storage.put("skill-1/v1.0.0/a.zip", b"a")
    storage.put("skill-1/v1.1.0/b.zip", b"b")
    storage.put("skill-2/v1.0.0/c.zip", b"c")
    storage.delete_prefix("skill-1/")
    assert not any(key.startswith("skills/skill-1/") for key in fake.objects)
    assert "skills/skill-2/v1.0.0/c.zip" in fake.objects
    assert not (tmp_path / "cache" / "skill-1").exists()


def test_missing_cos_credentials_degrade_to_local_in_dev(monkeypatch, tmp_path: Path) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "skill_storage", "cos")
    monkeypatch.setattr(settings, "cos_secret_id", "")
    monkeypatch.setattr(settings, "cos_secret_key", "")
    monkeypatch.setattr(settings, "environment", "dev")
    monkeypatch.setattr(settings, "skill_dir", str(tmp_path / "skills"))
    reset_skill_storage()
    try:
        assert get_skill_storage().backend == "local"
    finally:
        reset_skill_storage()


@pytest.mark.asyncio
async def test_materialize_async_handles_empty_key(tmp_path: Path) -> None:
    assert await materialize_async("", "") is None
    legacy = tmp_path / "legacy.zip"
    legacy.write_bytes(b"PK")
    assert await materialize_async("", str(legacy)) == legacy


def test_get_skill_storage_builds_cos_backend_when_configured(monkeypatch, tmp_path: Path) -> None:
    import qcloud_cos

    settings = get_settings()
    monkeypatch.setattr(settings, "skill_storage", "cos")
    monkeypatch.setattr(settings, "cos_secret_id", "fake-id")
    monkeypatch.setattr(settings, "cos_secret_key", "fake-key")
    monkeypatch.setattr(settings, "skill_cache_dir", str(tmp_path / "cache"))
    monkeypatch.setattr(qcloud_cos, "CosS3Client", lambda config: FakeCosClient())
    reset_skill_storage()
    try:
        assert get_skill_storage().backend == "cos"
    finally:
        reset_skill_storage()


def l3_package() -> bytes:
    import io
    import zipfile

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("FORMS.md", "Form filling guide.\n")
        archive.writestr("scripts/validate.py", "print('OK')\n")
    return buffer.getvalue()


@pytest.mark.asyncio
async def test_cos_backed_package_supports_l3_resource_read(tmp_path: Path, monkeypatch) -> None:
    """COS 权威存储 -> 缓存回源 -> L3 资源读取，全链路不依赖本地 var/skills。"""
    import json

    from app.runtime import tools as runtime_tools
    from app.services import skill_storage

    fake = FakeCosClient()
    storage = CosSkillStorage(fake_settings(tmp_path), tmp_path / "cache", client=fake)
    key = "skill-1/v1.0.0/pdf.zip"
    storage.put(key, l3_package())
    monkeypatch.setattr(skill_storage, "_storage", storage)

    first = await materialize_async(key)
    assert first is not None
    first.unlink()  # 强制缓存未命中，验证会从 COS 回源
    path = await materialize_async(key)
    assert path is not None and path.is_file()
    assert fake.get_calls == [storage.object_key(key)]

    snapshot = {
        "resolvedMounts": {"skill": [{
            "id": "skill-1", "name": "pdf", "description": "x",
            "instructions": "body", "version": "v1.0.0",
            "packagePath": str(path), "entry": "",
            "resources": ["FORMS.md"], "scripts": ["scripts/validate.py"],
        }]}
    }
    result = json.loads(await runtime_tools.skill_resource_runner(snapshot)(
        skillId="skill-1", path="FORMS.md"
    ))
    assert result["level"] == 3
    assert "Form filling guide" in result["content"]
