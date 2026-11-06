from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

AgentTheme = Literal["violet", "blue", "mint", "orange"]
ModelType = Literal["chat", "embedding", "rerank", "multimodal"]
ConnectionStatus = Literal["normal", "untested", "error"]
McpTransport = Literal["sse", "streamable_http", "stdio"]
LogType = Literal["session", "mcp", "skill", "tool", "model", "system"]
LogStatus = Literal["running", "success", "failed", "timeout"]
LogSource = Literal["test_session", "standard_api", "agui", "model_api", "system"]


class DomainModel(BaseModel):
    model_config = ConfigDict(extra="ignore")


class McpTool(DomainModel):
    id: str
    name: str
    key: str
    description: str
    inputSchema: dict[str, Any] = Field(default_factory=dict)
    enabled: bool


class McpServer(DomainModel):
    id: str
    name: str
    description: str
    transport: McpTransport
    endpoint: str
    headers: str
    timeoutSeconds: int
    qpm: int
    maxConcurrency: int
    enabled: bool
    connection: ConnectionStatus
    tools: list[McpTool]
    callCount: int
    successRate: float
    lastTestedAt: str | None = None
    createdAt: str
    updatedAt: str


class SkillVersion(DomainModel):
    version: str
    fileSize: str
    createdAt: str
    note: str


class Skill(DomainModel):
    id: str
    name: str
    description: str
    instructions: str
    version: str
    fileName: str
    fileSize: str
    sizeBytes: int
    referencedBy: int
    verified: bool
    versions: list[SkillVersion]
    createdAt: str
    updatedAt: str


class BuiltinTool(DomainModel):
    id: str
    name: str
    key: str
    description: str
    version: str
    enabled: bool
    callCount: int


class ModelPublication(DomainModel):
    id: str
    modelId: str
    version: str
    callIdentifier: str
    credentialSuffix: str
    headersConfigured: bool
    temperature: float
    maxOutputTokens: int
    qpm: int
    enabled: bool
    createdAt: str


class ModelResource(DomainModel):
    id: str
    name: str
    upstreamModelId: str
    type: ModelType
    vendor: str
    baseUrl: str
    credentialSuffix: str
    headers: str
    timeoutSeconds: int
    note: str
    enabled: bool
    connection: ConnectionStatus
    latencyMs: int
    lastTestedAt: str | None = None
    createdAt: str
    updatedAt: str


class AgentMounts(DomainModel):
    mcpIds: list[str] = Field(default_factory=list)
    skillIds: list[str] = Field(default_factory=list)
    toolIds: list[str] = Field(default_factory=list)
    modelIds: list[str] = Field(default_factory=list)


class AgentAdvanced(DomainModel):
    keepContext: bool = True
    autoRetry: bool = True
    outputThinkingSummary: bool = False
    maxToolCalls: int = 10
    maxRunSeconds: int = 120


class ModelParams(DomainModel):
    temperature: float = 0.7
    topP: float = 0.9
    maxOutputTokens: int = 4096


class Agent(DomainModel):
    id: str
    name: str
    description: str
    theme: AgentTheme
    defaultModelId: str | None = None
    testModelId: str | None = None
    systemPrompt: str
    variables: list[str]
    modelParams: ModelParams
    mounts: AgentMounts
    advanced: AgentAdvanced
    apiEnabled: bool
    aguiEnabled: bool
    credentialSuffix: str
    sessionCount: int
    createdAt: str
    updatedAt: str


class TokenUsage(DomainModel):
    inputTokens: int
    outputTokens: int
    totalTokens: int


class LogEntry(DomainModel):
    logId: str
    timestamp: str
    type: LogType
    status: LogStatus
    source: LogSource
    name: str
    summary: str
    runId: str | None = None
    threadId: str | None = None
    agentId: str | None = None
    resourceId: str | None = None
    durationMs: int | None = None
    inputSummary: str | None = None
    outputSummary: str | None = None
    tokenUsage: TokenUsage | None = None
    errorCode: str | None = None
    errorMessage: str | None = None
    credentialSuffix: str | None = None


class NotificationItem(DomainModel):
    id: str
    kind: Literal["error", "info", "success"]
    title: str
    summary: str
    timestamp: str
    targetPath: str
    read: bool


class WorkspaceSettings(DomainModel):
    workspaceName: str
    language: Literal["zh-CN", "en-US"]
    timezone: str
    domain: str
    telemetry: bool
    autoArchive: bool
    retentionDays: int
    accessKeySuffix: str


class ChatToolCall(DomainModel):
    name: str
    args: str
    status: Literal["success", "failed"]
    result: str


class ChatMessage(DomainModel):
    id: str
    role: Literal["user", "assistant", "tool"]
    content: str
    timestamp: str
    toolCall: ChatToolCall | None = None


class TrendPoint(DomainModel):
    date: str
    label: str
    count: int


class ExportTask(DomainModel):
    id: str
    createdAt: str
    format: Literal["csv", "json"]
    filters: str
    recordCount: int
    fileSizeBytes: int
    expiresAt: str
    status: Literal["pending", "completed", "expired"] = "completed"


class Trends(DomainModel):
    d14: list[TrendPoint]
    d30: list[TrendPoint]


class WorkspaceData(DomainModel):
    systemConfig: dict[str, Any] = Field(default_factory=dict)
    agents: list[Agent]
    models: list[ModelResource]
    publications: list[ModelPublication]
    mcps: list[McpServer]
    skills: list[Skill]
    tools: list[BuiltinTool]
    logs: list[LogEntry]
    notifications: list[NotificationItem]
    settings: WorkspaceSettings
    chatSessions: dict[str, list[ChatMessage]]
    trends: Trends
    exportTasks: list[ExportTask]


class PageResult(DomainModel):
    items: list[LogEntry]
    total: int
    page: int
    pageSize: int
    typeCounts: dict[str, int]


class LogDetail(LogEntry):
    chain: list[LogEntry] = Field(default_factory=list)


class ImpactResult(DomainModel):
    agents: list[Agent]
    names: list[str] = Field(default_factory=list)


class OverviewStats(DomainModel):
    agentsTotal: int
    agentsPublished: int
    todayCalls: int
    callsChangePercent: float
    modelsTotal: int
    modelsEnabled: int
    successRate: float
    avgDurationMs: int
    avgDurationChange: float
    abnormalCount: int
    typeCounts: dict[str, int]


class SearchResult(DomainModel):
    id: str
    name: str
    kind: Literal["agent", "model"]
    subtitle: str
    path: str


class TestConnectionResult(DomainModel):
    latencyMs: int
    httpStatus: int = 200
    errorCategory: str | None = None
    responseSummary: str = ""


class ToolDiscoveryResult(DomainModel):
    toolCount: int
    tools: list[dict[str, Any]] = Field(default_factory=list)
    latencyMs: int = 0


class McpToolTestResult(DomainModel):
    toolKey: str
    status: Literal["success", "failed"]
    durationMs: int
    output: str = ""
    error: str | None = None
    timestamp: str
    log: LogEntry | None = None


class ExportTaskAccepted(DomainModel):
    task: ExportTask
    downloadUrl: str | None = None


class SendResult(DomainModel):
    messages: list[ChatMessage]
    runId: str
    threadId: str
    logs: list[LogEntry] = Field(default_factory=list)
    sessionCount: int = 0


class PublishCheckResult(DomainModel):
    passed: bool
    issues: list[str] = Field(default_factory=list)


class HistoryResult(DomainModel):
    items: list[LogEntry]
    total: int
    sources: list[str] = Field(default_factory=list)
