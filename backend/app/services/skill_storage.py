"""Skill 包存储层：腾讯云 COS 权威存储 + 本地只读缓存。

设计要点：
- 上传、删除统一走存储后端，数据库只保存相对 key，不再保存容器内绝对路径。
- L3 脚本沙箱需要本地文件路径，由 materialize 把 COS 对象下载到缓存目录；
  key 含 skillId/version，版本内容不可变，因此缓存无需失效策略。
- 切换到 COS 前上传的历史包仍在本地：materialize 找不到对象时回退本地路径，
  并提供 scripts/migrate_skills_to_cos.py 做批量迁移。
- 凭据缺失时 dev 环境降级本地并明确告警；非 dev 由配置校验直接拒绝启动。
"""
from __future__ import annotations

import asyncio
import hashlib
import logging
import os
import shutil
from pathlib import Path
from typing import Any

from app.core.config import get_settings
from app.core.errors import ApiException

logger = logging.getLogger("albedo.skill.storage")


def normalize_key(key: str) -> str:
    cleaned = str(key or "").strip().replace("\\", "/").lstrip("/")
    if not cleaned or ".." in cleaned.split("/") or cleaned.startswith("./"):
        raise ApiException("validation_error", "Skill 对象 key 不合法", 400)
    return cleaned


def _read_body(body: Any) -> bytes:
    if hasattr(body, "get_raw_stream"):
        return body.get_raw_stream().read()
    if hasattr(body, "read"):
        return body.read()
    raise ApiException("skill_storage_unavailable", "对象存储返回了无法读取的内容", 502)


def _is_not_found(exc: Exception) -> bool:
    text = str(exc)
    return "404" in text or "NoSuchKey" in text or "NoSuchResource" in text


class LocalSkillStorage:
    """单机/离线存储：包直接落在 skill_dir 下。"""

    backend = "local"

    def __init__(self, root: Path) -> None:
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)

    def uri(self, key: str) -> str:
        return str(self.root / normalize_key(key))

    def put(self, key: str, content: bytes) -> str:
        target = self.root / normalize_key(key)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        return str(target)

    def fetch(self, key: str) -> bytes | None:
        target = self.root / normalize_key(key)
        return target.read_bytes() if target.is_file() else None

    def delete(self, key: str) -> None:
        (self.root / normalize_key(key)).unlink(missing_ok=True)

    def delete_prefix(self, prefix: str) -> None:
        shutil.rmtree(self.root / normalize_key(prefix), ignore_errors=True)

    def materialize(self, key: str, legacy_path: str = "") -> Path | None:
        target = self.root / normalize_key(key)
        if target.is_file():
            return target
        legacy = _existing_file(legacy_path)
        return legacy


class CosSkillStorage:
    """腾讯云 COS 存储；client 可注入以便离线测试。"""

    backend = "cos"

    def __init__(self, settings: Any, cache_dir: Path, client: Any = None) -> None:
        if client is None:
            from qcloud_cos import CosConfig, CosS3Client

            options: dict[str, Any] = {
                "Region": settings.cos_region,
                "SecretId": settings.cos_secret_id,
                "SecretKey": settings.cos_secret_key,
                "Token": settings.cos_token or None,
                "Scheme": settings.cos_scheme,
                "Timeout": settings.cos_timeout_seconds,
            }
            if settings.cos_endpoint:
                options["Endpoint"] = settings.cos_endpoint
            client = CosS3Client(CosConfig(**options))
        self._client = client
        self.bucket = settings.cos_bucket
        raw_prefix = str(settings.cos_prefix or "").strip("/")
        self.prefix = normalize_key(raw_prefix) if raw_prefix else ""
        self.cache_dir = cache_dir
        self.legacy_root = Path(settings.skill_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    def object_key(self, key: str) -> str:
        normalized = normalize_key(key)
        return f"{self.prefix}/{normalized}" if self.prefix else normalized

    def uri(self, key: str) -> str:
        return f"cos://{self.bucket}/{self.object_key(key)}"

    def _cache_path(self, key: str) -> Path:
        parts = normalize_key(key).split("/")
        digest = hashlib.sha256(normalize_key(key).encode("utf-8")).hexdigest()[:16]
        group = parts[0] if parts else "_"
        version = parts[1] if len(parts) > 1 else "_"
        filename = Path(parts[-1]).name
        return self.cache_dir / group / version / f"{digest}-{filename}"

    def _write_cache(self, key: str, content: bytes) -> Path:
        target = self._cache_path(key)
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".tmp")
        temporary.write_bytes(content)
        os.replace(temporary, target)
        return target

    def put(self, key: str, content: bytes) -> str:
        object_key = self.object_key(key)
        try:
            self._client.put_object(
                Bucket=self.bucket, Key=object_key, Body=content, ContentType="application/zip"
            )
        except Exception as exc:  # noqa: BLE001
            logger.error("COS 上传失败 key=%s：%s", object_key, exc)
            raise ApiException("skill_storage_unavailable", "Skill 包上传对象存储失败", 502) from exc
        # 同步填充缓存，保证刚上传的版本在同一次请求后立即可被 L3 读取。
        self._write_cache(key, content)
        return self.uri(key)

    def fetch(self, key: str) -> bytes | None:
        object_key = self.object_key(key)
        try:
            response = self._client.get_object(Bucket=self.bucket, Key=object_key)
            return _read_body(response["Body"])
        except Exception as exc:  # noqa: BLE001
            if not _is_not_found(exc):
                logger.warning("COS 读取失败 key=%s：%s", object_key, exc)
            return None

    def delete(self, key: str) -> None:
        try:
            self._client.delete_object(Bucket=self.bucket, Key=self.object_key(key))
        except Exception as exc:  # noqa: BLE001
            logger.warning("COS 删除失败 key=%s：%s", key, exc)
        self._cache_path(key).unlink(missing_ok=True)

    def delete_prefix(self, prefix: str) -> None:
        object_prefix = self.object_key(prefix).rstrip("/") + "/"
        marker = ""
        while True:
            try:
                response = self._client.list_objects(
                    Bucket=self.bucket, Prefix=object_prefix, Marker=marker, MaxKeys=1000
                )
            except Exception as exc:  # noqa: BLE001
                logger.warning("COS 列举失败 prefix=%s：%s", object_prefix, exc)
                break
            contents = response.get("Contents") or []
            if isinstance(contents, dict):
                contents = [contents]
            for item in contents:
                try:
                    self._client.delete_object(Bucket=self.bucket, Key=item["Key"])
                except Exception as exc:  # noqa: BLE001
                    logger.warning("COS 删除失败 key=%s：%s", item.get("Key"), exc)
            if str(response.get("IsTruncated", "false")).lower() != "true" or not contents:
                break
            marker = str(response.get("NextMarker") or contents[-1]["Key"])
        group = normalize_key(prefix).split("/")[0]
        shutil.rmtree(self.cache_dir / group, ignore_errors=True)

    def materialize(self, key: str, legacy_path: str = "") -> Path | None:
        cached = self._cache_path(key)
        if cached.is_file():
            return cached
        content = self.fetch(key)
        if content is not None:
            return self._write_cache(key, content)
        # 切换 COS 之前上传的历史包：先按显式路径，再按 skill_dir/key 回退。
        legacy = _existing_file(legacy_path)
        if legacy is not None:
            logger.info("COS 未找到 %s，回退本地历史包 %s", key, legacy)
            return legacy
        candidate = self.legacy_root / normalize_key(key)
        return candidate if candidate.is_file() else None


def _existing_file(raw: str) -> Path | None:
    if not raw or str(raw).startswith("cos://"):
        return None
    candidate = Path(str(raw))
    return candidate if candidate.is_file() else None


_storage: Any = None
_fallback_logged = False


def reset_skill_storage() -> None:
    """仅供测试与配置热更新使用。"""
    global _storage, _fallback_logged
    _storage = None
    _fallback_logged = False


def get_skill_storage() -> LocalSkillStorage | CosSkillStorage:
    global _storage, _fallback_logged
    if _storage is not None:
        return _storage
    settings = get_settings()
    if settings.skill_storage == "local":
        _storage = LocalSkillStorage(Path(settings.skill_dir))
    elif settings.cos_configured:
        _storage = CosSkillStorage(settings, Path(settings.skill_cache_dir))
    else:
        if settings.environment != "dev":
            raise ApiException(
                "skill_storage_unavailable",
                "SKILL_STORAGE=cos 但未配置 COS_SECRET_ID / COS_SECRET_KEY",
                500,
            )
        if not _fallback_logged:
            logger.warning(
                "COS 凭据未配置：Skill 包存储降级为本地目录 %s；生产环境必须配置 COS 凭据",
                settings.skill_dir,
            )
            _fallback_logged = True
        _storage = LocalSkillStorage(Path(settings.skill_dir))
    logger.info("Skill 包存储后端：%s", _storage.backend)
    return _storage


async def put_async(key: str, content: bytes) -> str:
    return await asyncio.to_thread(get_skill_storage().put, key, content)


async def delete_async(key: str) -> None:
    await asyncio.to_thread(get_skill_storage().delete, key)


async def delete_prefix_async(prefix: str) -> None:
    await asyncio.to_thread(get_skill_storage().delete_prefix, prefix)


async def materialize_async(key: str, legacy_path: str = "") -> Path | None:
    if not key and not legacy_path:
        return None
    if not key:
        return _existing_file(legacy_path)
    return await asyncio.to_thread(get_skill_storage().materialize, key, legacy_path)
