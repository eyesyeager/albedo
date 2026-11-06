import json
from typing import Any
from urllib.parse import urlparse

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiException
from app.core.security import decrypt_secret, encrypt_secret, hash_secret, now_iso, suffix_of
from app.db import models as m
from app.services.audit import audit
from app.services.snapshot import row_dict


def validate_name(value: str, label: str = "名称", max_length: int = 50) -> str:
    trimmed = (value or "").strip()
    if not trimmed:
        raise ApiException("validation_error", f"{label}不能为空", 400)
    if len(trimmed) > max_length:
        raise ApiException("validation_error", f"{label}不能超过 {max_length} 个字符", 400)
    return trimmed


def validate_blank(value: str, message: str) -> str:
    trimmed = (value or "").strip()
    if not trimmed:
        raise ApiException("validation_error", message, 400)
    return trimmed


def validate_json_object(text: str, message: str = "自定义请求头必须是合法的 JSON 对象") -> dict[str, Any]:
    if not (text or "").strip():
        return {}
    try:
        parsed = json.loads(text)
    except (json.JSONDecodeError, TypeError) as exc:
        raise ApiException("validation_error", message, 400) from exc
    if not isinstance(parsed, dict):
        raise ApiException("validation_error", message, 400)
    return parsed


def validate_http_url(value: str, message: str) -> str:
    trimmed = (value or "").strip()
    try:
        parsed = urlparse(trimmed)
    except ValueError as exc:
        raise ApiException("validation_error", message, 400) from exc
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise ApiException("validation_error", message, 400)
    return trimmed


def read_headers(value: str | None) -> str:
    if not value:
        return ""
    return decrypt_secret(value) or value


def write_headers(value: str | None) -> str:
    if not value:
        return ""
    return encrypt_secret(value)


def model_to_dict(row: m.ModelResource) -> dict[str, Any]:
    data = row_dict(row, rename={"headers_enc": "headers"})
    data["headers"] = read_headers(row.headers_enc)
    return data


def publication_to_dict(row: m.ModelPublication) -> dict[str, Any]:
    return row_dict(row, drop={"headers_enc"}, extra={"headersConfigured": bool(row.headers_enc)})


def tool_to_dict(row: m.BuiltinTool) -> dict[str, Any]:
    return row_dict(row)


async def mcp_to_dict(session: AsyncSession, row: m.McpServer) -> dict[str, Any]:
    data = row_dict(row, rename={"headers_enc": "headers"})
    data["headers"] = read_headers(row.headers_enc)
    tools = (await session.scalars(select(m.McpTool).where(m.McpTool.server_id == row.id).order_by(m.McpTool.id))).all()
    data["tools"] = [row_dict(item, drop={"server_id"}) for item in tools]
    return data


async def skill_to_dict(session: AsyncSession, row: m.Skill) -> dict[str, Any]:
    data = row_dict(row)
    versions = (
        await session.scalars(
            select(m.SkillVersion).where(m.SkillVersion.skill_id == row.id).order_by(m.SkillVersion.created_at.desc())
        )
    ).all()
    data["versions"] = [row_dict(item, drop={"id", "skill_id", "file_path", "manifest"}) for item in versions]
    return data


async def get_or_404(session: AsyncSession, model: type, entity_id: str) -> Any:
    row = await session.get(model, entity_id)
    if row is None:
        raise ApiException("resource_not_found", "未找到该资源，请刷新页面后重试", 404)
    return row


async def store_credential(
    session: AsyncSession,
    kind: str,
    resource_id: str,
    plain: str,
    suffix: str | None = None,
) -> None:
    existing = await session.scalar(
        select(m.Credential).where(m.Credential.type == kind, m.Credential.resource_id == resource_id)
    )
    stamp = now_iso()
    if existing is None:
        session.add(m.Credential(
            id=f"cred-{kind}-{resource_id}",
            type=kind,
            hash=hash_secret(plain),
            cipher=encrypt_secret(plain),
            suffix=suffix or suffix_of(plain),
            resource_id=resource_id,
            rotated_at=stamp,
            created_at=stamp,
        ))
        return
    existing.hash = hash_secret(plain)
    existing.cipher = encrypt_secret(plain)
    existing.suffix = suffix or suffix_of(plain)
    existing.rotated_at = stamp


async def agent_to_dict(session: AsyncSession, row: m.Agent) -> dict[str, Any]:
    mount_rows = (await session.scalars(select(m.AgentMount).where(m.AgentMount.agent_id == row.id))).all()
    keys = {"mcp": "mcpIds", "skill": "skillIds", "tool": "toolIds", "model": "modelIds"}
    grouped: dict[str, list[str]] = {"mcpIds": [], "skillIds": [], "toolIds": [], "modelIds": []}
    for mount in mount_rows:
        grouped[keys[mount.kind]].append(mount.resource_id)
    return row_dict(row, drop={"version"}, extra={"mounts": grouped})


async def commit_write(
    session: AsyncSession,
    entity: dict[str, Any] | None,
    name: str,
    summary: str,
    *,
    resource_id: str | None = None,
    **extra: Any,
) -> dict[str, Any]:
    log = await audit(session, name, summary, resource_id=resource_id)
    await session.commit()
    return {"entity": entity, "log": log, **extra}
