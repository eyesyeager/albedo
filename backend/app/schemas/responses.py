from typing import Any

from app.schemas.domain import DomainModel, LogEntry


class LoginResponse(DomainModel):
    authenticated: bool
    workspaceName: str = ""
    log: LogEntry | None = None


class SessionResponse(DomainModel):
    authenticated: bool
    workspaceName: str = ""


class LogoutResponse(DomainModel):
    ok: bool
    log: LogEntry | None = None


class WriteResponse(DomainModel):
    entity: dict[str, Any] | None = None
    log: LogEntry | None = None
    logs: list[LogEntry] | None = None
    version: int | None = None
    updatedAt: str | None = None
    fullKey: str | None = None
    credentialSuffix: str | None = None
    publication: dict[str, Any] | None = None
    settings: dict[str, Any] | None = None
    notifications: list[dict[str, Any]] | None = None
    sessionCount: int | None = None
    task: dict[str, Any] | None = None
    logs: list[LogEntry] | None = None
    downloadUrl: str | None = None
    latencyMs: int | None = None
    toolCount: int | None = None


class OkResponse(DomainModel):
    ok: bool = True
