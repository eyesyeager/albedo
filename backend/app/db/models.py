from typing import Any

from sqlalchemy import JSON, Boolean, Float, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base

ISO = String(40)
ID = String(64)


class WorkspaceSetting(Base):
    __tablename__ = "workspace_settings"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, default=1)
    workspace_name: Mapped[str] = mapped_column(String(128), default="Albedo")
    language: Mapped[str] = mapped_column(String(16), default="zh-CN")
    timezone: Mapped[str] = mapped_column(String(64), default="Asia/Shanghai")
    domain: Mapped[str] = mapped_column(String(255), default="")
    telemetry: Mapped[bool] = mapped_column(Boolean, default=True)
    auto_archive: Mapped[bool] = mapped_column(Boolean, default=True)
    retention_days: Mapped[int] = mapped_column(Integer, default=90)
    access_key_suffix: Mapped[str] = mapped_column(String(16), default="")
    updated_at: Mapped[str] = mapped_column(ISO, default="")


class SystemConfig(Base):
    __tablename__ = "system_configs"

    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    value: Mapped[Any] = mapped_column(JSON)
    description: Mapped[str] = mapped_column(String(255), default="")
    updated_at: Mapped[str] = mapped_column(ISO, default="")


class Credential(Base):
    __tablename__ = "credentials"

    id: Mapped[str] = mapped_column(ID, primary_key=True)
    type: Mapped[str] = mapped_column(String(48), index=True)
    hash: Mapped[str] = mapped_column(Text, default="")
    cipher: Mapped[str] = mapped_column(Text, default="")
    suffix: Mapped[str] = mapped_column(String(16), default="")
    resource_id: Mapped[str] = mapped_column(String(128), default="", index=True)
    rotated_at: Mapped[str] = mapped_column(ISO, default="")
    created_at: Mapped[str] = mapped_column(ISO, default="")


class Session(Base):
    __tablename__ = "sessions"

    id: Mapped[str] = mapped_column(ID, primary_key=True)
    user_id: Mapped[str] = mapped_column(String(64), default="owner", index=True)
    created_at: Mapped[str] = mapped_column(ISO, default="")
    idle_expires_at: Mapped[str] = mapped_column(ISO, default="")
    absolute_expires_at: Mapped[str] = mapped_column(ISO, default="")
    revoked_at: Mapped[str | None] = mapped_column(ISO, nullable=True)


class Agent(Base):
    __tablename__ = "agents"

    id: Mapped[str] = mapped_column(ID, primary_key=True)
    name: Mapped[str] = mapped_column(String(128))
    description: Mapped[str] = mapped_column(Text, default="")
    theme: Mapped[str] = mapped_column(String(16), default="violet")
    default_model_id: Mapped[str | None] = mapped_column(ID, nullable=True)
    test_model_id: Mapped[str | None] = mapped_column(ID, nullable=True)
    system_prompt: Mapped[str] = mapped_column(Text, default="")
    variables: Mapped[list[str]] = mapped_column(JSON, default=list)
    model_params: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    advanced: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    api_enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    agui_enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    credential_suffix: Mapped[str] = mapped_column(String(16), default="")
    session_count: Mapped[int] = mapped_column(Integer, default=0)
    version: Mapped[int] = mapped_column(Integer, default=1)
    created_at: Mapped[str] = mapped_column(ISO, default="")
    updated_at: Mapped[str] = mapped_column(ISO, default="")


class AgentMount(Base):
    __tablename__ = "agent_mounts"
    __table_args__ = (UniqueConstraint("agent_id", "kind", "resource_id", name="uq_agent_mount_resource"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    agent_id: Mapped[str] = mapped_column(ID, index=True)
    kind: Mapped[str] = mapped_column(String(16))
    resource_id: Mapped[str] = mapped_column(ID)
    skill_version: Mapped[str] = mapped_column(String(32), default="")


class ModelResource(Base):
    __tablename__ = "models"

    id: Mapped[str] = mapped_column(ID, primary_key=True)
    name: Mapped[str] = mapped_column(String(128))
    upstream_model_id: Mapped[str] = mapped_column(String(255), default="")
    type: Mapped[str] = mapped_column(String(24), default="chat")
    vendor: Mapped[str] = mapped_column(String(128), default="")
    base_url: Mapped[str] = mapped_column(Text, default="")
    credential_suffix: Mapped[str] = mapped_column(String(16), default="")
    headers_enc: Mapped[str] = mapped_column(Text, default="")
    timeout_seconds: Mapped[int] = mapped_column(Integer, default=60)
    note: Mapped[str] = mapped_column(Text, default="")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    connection: Mapped[str] = mapped_column(String(16), default="untested")
    latency_ms: Mapped[int] = mapped_column(Integer, default=0)
    last_tested_at: Mapped[str | None] = mapped_column(ISO, nullable=True)
    created_at: Mapped[str] = mapped_column(ISO, default="")
    updated_at: Mapped[str] = mapped_column(ISO, default="")


class ModelPublication(Base):
    __tablename__ = "model_publications"
    __table_args__ = (
        UniqueConstraint("call_identifier", name="uq_publication_call_identifier"),
        UniqueConstraint("model_id", "version", name="uq_publication_model_version"),
    )

    id: Mapped[str] = mapped_column(ID, primary_key=True)
    model_id: Mapped[str] = mapped_column(ID, index=True)
    version: Mapped[str] = mapped_column(String(32))
    call_identifier: Mapped[str] = mapped_column(String(128))
    credential_suffix: Mapped[str] = mapped_column(String(16), default="")
    headers_enc: Mapped[str] = mapped_column(Text, default="")
    temperature: Mapped[float] = mapped_column(Float, default=0.7)
    max_output_tokens: Mapped[int] = mapped_column(Integer, default=4096)
    qpm: Mapped[int] = mapped_column(Integer, default=60)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[str] = mapped_column(ISO, default="")


class McpServer(Base):
    __tablename__ = "mcp_servers"

    id: Mapped[str] = mapped_column(ID, primary_key=True)
    name: Mapped[str] = mapped_column(String(128))
    description: Mapped[str] = mapped_column(Text, default="")
    transport: Mapped[str] = mapped_column(String(24), default="streamable_http")
    endpoint: Mapped[str] = mapped_column(Text, default="")
    headers_enc: Mapped[str] = mapped_column(Text, default="")
    env_enc: Mapped[str] = mapped_column(Text, default="")
    timeout_seconds: Mapped[int] = mapped_column(Integer, default=60)
    qpm: Mapped[int] = mapped_column(Integer, default=60)
    max_concurrency: Mapped[int] = mapped_column(Integer, default=5)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    connection: Mapped[str] = mapped_column(String(16), default="untested")
    call_count: Mapped[int] = mapped_column(Integer, default=0)
    success_rate: Mapped[float] = mapped_column(Float, default=100.0)
    last_tested_at: Mapped[str | None] = mapped_column(ISO, nullable=True)
    created_at: Mapped[str] = mapped_column(ISO, default="")
    updated_at: Mapped[str] = mapped_column(ISO, default="")


class McpTool(Base):
    __tablename__ = "mcp_tools"
    __table_args__ = (UniqueConstraint("server_id", "key", name="uq_mcp_tool_key"),)

    id: Mapped[str] = mapped_column(ID, primary_key=True)
    server_id: Mapped[str] = mapped_column(ID, index=True)
    name: Mapped[str] = mapped_column(String(128))
    key: Mapped[str] = mapped_column(String(128))
    description: Mapped[str] = mapped_column(Text, default="")
    input_schema: Mapped[str | None] = mapped_column(Text, nullable=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)


class McpCallRecord(Base):
    __tablename__ = "mcp_call_records"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    server_id: Mapped[str] = mapped_column(ID, index=True)
    tool_key: Mapped[str] = mapped_column(String(128), default="")
    run_id: Mapped[str] = mapped_column(String(64), default="")
    status: Mapped[str] = mapped_column(String(16), default="success")
    duration_ms: Mapped[int] = mapped_column(Integer, default=0)
    timestamp: Mapped[str] = mapped_column(ISO, default="")


class Skill(Base):
    __tablename__ = "skills"

    id: Mapped[str] = mapped_column(ID, primary_key=True)
    name: Mapped[str] = mapped_column(String(128))
    description: Mapped[str] = mapped_column(Text, default="")
    instructions: Mapped[str] = mapped_column(Text, default="")
    version: Mapped[str] = mapped_column(String(32), default="v1.0.0")
    file_name: Mapped[str] = mapped_column(String(255), default="")
    file_size: Mapped[str] = mapped_column(String(32), default="")
    size_bytes: Mapped[int] = mapped_column(Integer, default=0)
    referenced_by: Mapped[int] = mapped_column(Integer, default=0)
    verified: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[str] = mapped_column(ISO, default="")
    updated_at: Mapped[str] = mapped_column(ISO, default="")


class SkillVersion(Base):
    __tablename__ = "skill_versions"
    __table_args__ = (UniqueConstraint("skill_id", "version", name="uq_skill_version"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    skill_id: Mapped[str] = mapped_column(ID, index=True)
    version: Mapped[str] = mapped_column(String(32))
    file_size: Mapped[str] = mapped_column(String(32), default="")
    file_path: Mapped[str] = mapped_column(Text, default="")
    manifest: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    note: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[str] = mapped_column(ISO, default="")


class BuiltinTool(Base):
    __tablename__ = "builtin_tools"

    id: Mapped[str] = mapped_column(ID, primary_key=True)
    key: Mapped[str] = mapped_column(String(64), unique=True)
    name: Mapped[str] = mapped_column(String(128))
    description: Mapped[str] = mapped_column(Text, default="")
    version: Mapped[str] = mapped_column(String(32), default="v1.0.0")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    call_count: Mapped[int] = mapped_column(Integer, default=0)


class Run(Base):
    __tablename__ = "runs"

    run_id: Mapped[str] = mapped_column(ID, primary_key=True)
    thread_id: Mapped[str] = mapped_column(ID, default="", index=True)
    agent_id: Mapped[str] = mapped_column(ID, default="", index=True)
    source: Mapped[str] = mapped_column(String(24), default="system")
    status: Mapped[str] = mapped_column(String(24), default="completed")
    config_snapshot: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    finish_reason: Mapped[str] = mapped_column(String(32), default="stop")
    usage: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    duration_ms: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[str] = mapped_column(ISO, default="")
    updated_at: Mapped[str] = mapped_column(ISO, default="")


class ChatMessage(Base):
    __tablename__ = "chat_messages"

    id: Mapped[str] = mapped_column(ID, primary_key=True)
    thread_id: Mapped[str] = mapped_column(ID, default="")
    run_id: Mapped[str] = mapped_column(ID, default="")
    agent_id: Mapped[str] = mapped_column(ID, index=True)
    role: Mapped[str] = mapped_column(String(16), default="user")
    content: Mapped[str] = mapped_column(Text, default="")
    tool_call: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    timestamp: Mapped[str] = mapped_column(ISO, default="")


class LogEntry(Base):
    __tablename__ = "log_entries"
    __table_args__ = (
        Index("ix_log_run_time", "run_id", "timestamp"),
        Index("ix_log_type_status_time", "type", "status", "timestamp"),
    )

    log_id: Mapped[str] = mapped_column(ID, primary_key=True)
    timestamp: Mapped[str] = mapped_column(ISO, default="", index=True)
    type: Mapped[str] = mapped_column(String(16), default="system")
    status: Mapped[str] = mapped_column(String(16), default="success")
    source: Mapped[str] = mapped_column(String(24), default="system")
    name: Mapped[str] = mapped_column(String(255), default="")
    summary: Mapped[str] = mapped_column(Text, default="")
    run_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    thread_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    agent_id: Mapped[str | None] = mapped_column(ID, nullable=True)
    resource_id: Mapped[str | None] = mapped_column(ID, nullable=True)
    duration_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    input_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    output_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    token_usage: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    error_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    credential_suffix: Mapped[str | None] = mapped_column(String(16), nullable=True)


class ExportTask(Base):
    __tablename__ = "export_tasks"

    id: Mapped[str] = mapped_column(ID, primary_key=True)
    created_at: Mapped[str] = mapped_column(ISO, default="")
    format: Mapped[str] = mapped_column(String(8), default="csv")
    filters_json: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    filters_label: Mapped[str] = mapped_column(Text, default="")
    record_count: Mapped[int] = mapped_column(Integer, default=0)
    file_size_bytes: Mapped[int] = mapped_column(Integer, default=0)
    expires_at: Mapped[str] = mapped_column(ISO, default="")
    status: Mapped[str] = mapped_column(String(16), default="pending")
    file_path: Mapped[str] = mapped_column(Text, default="")


class Notification(Base):
    __tablename__ = "notifications"

    id: Mapped[str] = mapped_column(ID, primary_key=True)
    kind: Mapped[str] = mapped_column(String(16), default="info")
    title: Mapped[str] = mapped_column(String(255), default="")
    summary: Mapped[str] = mapped_column(Text, default="")
    timestamp: Mapped[str] = mapped_column(ISO, default="")
    target_path: Mapped[str] = mapped_column(String(255), default="")
    read: Mapped[bool] = mapped_column(Boolean, default=False)


class TrendDaily(Base):
    __tablename__ = "trend_daily"

    date: Mapped[str] = mapped_column(String(10), primary_key=True)
    call_count: Mapped[int] = mapped_column(Integer, default=0)
    success_count: Mapped[int] = mapped_column(Integer, default=0)
    total_duration_ms: Mapped[int] = mapped_column(Integer, default=0)
