import json
import logging
from typing import Any
from urllib.parse import urlparse

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiException
from app.core.security import decrypt_secret, encrypt_secret, hash_secret, now_iso, suffix_of
from app.db import models as m
from app.services.audit import audit
from app.services.snapshot import row_dict, tool_schema_dict


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
    """解密自定义头；失败时返回空串，绝不把 Fernet 密文当明文回显或发往上游。"""
    if not value:
        return ""
    plaintext = decrypt_secret(value)
    if plaintext is None:
        logging.getLogger("albedo.credentials").warning("自定义请求头解密失败，已按空值处理")
        return ""
    return plaintext


def write_headers(value: str | None) -> str:
    if not value:
        return ""
    return encrypt_secret(value)


def model_to_dict(row: m.ModelResource) -> dict[str, Any]:
    # 常规读接口只下发「是否已配置」，不下发解密后的请求头或 Fernet 密文。
    data = row_dict(row, drop={"headers_enc"})
    data["headers"] = ""
    data["headersConfigured"] = bool(row.headers_enc)
    return data


def publication_to_dict(row: m.ModelPublication) -> dict[str, Any]:
    return row_dict(row, drop={"headers_enc"}, extra={"headersConfigured": bool(row.headers_enc)})


def tool_to_dict(row: m.BuiltinTool) -> dict[str, Any]:
    return row_dict(row)


async def mcp_to_dict(session: AsyncSession, row: m.McpServer) -> dict[str, Any]:
    data = row_dict(row, drop={"headers_enc", "env_enc"})
    data["headers"] = ""
    data["env"] = ""
    data["headersConfigured"] = bool(row.headers_enc)
    data["envConfigured"] = bool(row.env_enc)
    tools = (await session.scalars(select(m.McpTool).where(m.McpTool.server_id == row.id).order_by(m.McpTool.id))).all()
    data["tools"] = [
        {
            **row_dict(item, drop={"server_id", "input_schema"}),
            "inputSchema": tool_schema_dict(item.input_schema),
        }
        for item in tools
    ]
    return data


async def skill_to_dict(session: AsyncSession, row: m.Skill) -> dict[str, Any]:
    data = row_dict(row)
    versions = (
        await session.scalars(
            select(m.SkillVersion).where(m.SkillVersion.skill_id == row.id).order_by(m.SkillVersion.created_at.desc())
        )
    ).all()
    data["versions"] = [
        row_dict(item, drop={"id", "skill_id", "file_path", "manifest", "instructions", "frontmatter", "package_key", "entry"})
        for item in versions
    ]
    # 引用数由挂载关系实时统计，不复用库内可能过期的 referenced_by 计数。
    data["referencedBy"] = int(
        await session.scalar(
            select(func.count())
            .select_from(m.AgentMount)
            .where(m.AgentMount.kind == "skill", m.AgentMount.resource_id == row.id)
        )
        or 0
    )
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
        # autoflush=False 下必须显式 flush，否则同事务内后续查询看不到新凭证。
        await session.flush()
        return
    existing.hash = hash_secret(plain)
    existing.cipher = encrypt_secret(plain)
    existing.suffix = suffix or suffix_of(plain)
    existing.rotated_at = stamp


async def reveal_credential(
    session: AsyncSession,
    kind: str,
    resource_id: str,
    label: str,
) -> dict[str, Any]:
    """按需解密并返回完整凭证；只写审计日志，明文不进入 bootstrap / 常规资源响应。"""
    credential = await session.scalar(
        select(m.Credential).where(m.Credential.type == kind, m.Credential.resource_id == resource_id)
    )
    if credential is None or not credential.cipher:
        raise ApiException("credential_not_found", "未找到该资源的访问凭证，请重新创建或重置密钥", 404)
    full_key = decrypt_secret(credential.cipher)
    if not full_key:
        raise ApiException("credential_unreadable", "凭证解密失败，请检查 SECRET_KEY 配置后重试", 500)
    await audit(
        session, f"查看{label}",
        f"查看了 {resource_id} 的{label}完整值（后缀 {credential.suffix}）",
        resource_id=resource_id,
    )
    await session.commit()
    return {
        "fullKey": full_key,
        "credentialSuffix": credential.suffix,
        "rotatedAt": credential.rotated_at,
    }


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
