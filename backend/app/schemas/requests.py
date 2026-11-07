from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class PatchModel(BaseModel):
    model_config = ConfigDict(extra="ignore")


class LoginRequest(PatchModel):
    key: str


class AgentDraft(PatchModel):
    name: str
    description: str = ""
    defaultModelId: str
    systemPrompt: str = ""


class AgentPatch(PatchModel):
    name: str | None = None
    description: str | None = None
    theme: Literal["violet", "blue", "mint", "orange"] | None = None
    defaultModelId: str | None = None
    testModelId: str | None = None
    systemPrompt: str | None = None
    variables: list[str] | None = None
    modelParams: dict[str, Any] | None = None
    mounts: dict[str, list[str]] | None = None
    advanced: dict[str, Any] | None = None
    apiEnabled: bool | None = None
    aguiEnabled: bool | None = None
    credentialSuffix: str | None = None
    sessionCount: int | None = None
    expectedVersion: int | None = None


class ProtocolPatch(PatchModel):
    protocol: Literal["api", "agui"]
    enabled: bool


class ModelTestRequest(PatchModel):
    name: str = ""
    upstreamModelId: str = ""
    type: Literal["chat", "embedding", "rerank", "multimodal"] = "chat"
    vendor: str = ""
    baseUrl: str = ""
    apiKey: str = ""
    headers: str = ""
    timeoutSeconds: int = 60
    note: str = ""
    enabled: bool = True


class ModelDraft(ModelTestRequest):
    tested: bool = False


class ModelPatch(PatchModel):
    name: str | None = None
    upstreamModelId: str | None = None
    type: Literal["chat", "embedding", "rerank", "multimodal"] | None = None
    vendor: str | None = None
    baseUrl: str | None = None
    apiKey: str | None = None
    headers: str | None = None
    timeoutSeconds: int | None = None
    note: str | None = None
    enabled: bool | None = None
    credentialSuffix: str | None = None
    connection: Literal["normal", "untested", "error"] | None = None
    latencyMs: int | None = None
    lastTestedAt: str | None = None


class PublicationDraft(PatchModel):
    version: str
    callIdentifier: str
    apiKey: str
    headers: str = ""
    temperature: float = 0.7
    maxOutputTokens: int = 4096
    qpm: int = 60


class PublicationPatch(PatchModel):
    enabled: bool | None = None
    temperature: float | None = None
    maxOutputTokens: int | None = None
    qpm: int | None = None


class McpTestRequest(PatchModel):
    name: str = ""
    description: str = ""
    transport: Literal["sse", "streamable_http", "stdio"] = "streamable_http"
    endpoint: str = ""
    headers: str = ""
    env: str = ""
    timeoutSeconds: int = 60
    tested: bool = False


class McpDraft(McpTestRequest):
    pass


class McpToolTestRequest(PatchModel):
    arguments: dict[str, Any] = Field(default_factory=dict)
    timeoutSeconds: int = 30


class McpPatch(PatchModel):
    name: str | None = None
    description: str | None = None
    transport: Literal["sse", "streamable_http", "stdio"] | None = None
    endpoint: str | None = None
    headers: str | None = None
    env: str | None = None
    timeoutSeconds: int | None = None
    qpm: int | None = None
    maxConcurrency: int | None = None
    enabled: bool | None = None
    connection: Literal["normal", "untested", "error"] | None = None


class UsagePatch(PatchModel):
    qpm: int
    maxConcurrency: int


class AccessKeyDraft(PatchModel):
    current: str
    next: str
    confirm: str


class SettingsPatch(PatchModel):
    workspaceName: str | None = None
    language: Literal["zh-CN", "en-US"] | None = None
    timezone: str | None = None
    domain: str | None = None
    telemetry: bool | None = None
    autoArchive: bool | None = None
    retentionDays: int | None = None


class ExportRequest(PatchModel):
    format: Literal["csv", "json"]
    filters: dict[str, Any] = Field(default_factory=dict)
    filtersLabel: str = ""
    logIds: list[str] = Field(default_factory=list)


class EnabledPatch(PatchModel):
    enabled: bool


class TestRunRequest(PatchModel):
    content: str
    testModelId: str = ""
    threadId: str = ""
    variables: dict[str, str] = Field(default_factory=dict)


class LogFilters(PatchModel):
    keyword: str = ""
    type: str = "all"
    status: str = "all"
    range: str = "7d"
    agentId: str = "all"
