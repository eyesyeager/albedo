export type AgentTheme = 'violet' | 'blue' | 'mint' | 'orange'
export type ModelType = 'chat' | 'embedding' | 'rerank' | 'multimodal'
export type ConnectionStatus = 'normal' | 'untested' | 'error'
export type McpTransport = 'sse' | 'streamable_http' | 'stdio'
export type LogType = 'session' | 'mcp' | 'skill' | 'tool' | 'model' | 'system'
export type LogStatus = 'running' | 'success' | 'failed' | 'timeout'
export type LogSource = 'test_session' | 'standard_api' | 'agui' | 'model_api' | 'system'

export interface McpToolSchemaProperty {
  type?: string
  description?: string
  default?: unknown
  enum?: unknown[]
  items?: McpToolSchemaProperty
  anyOf?: McpToolSchemaProperty[]
  oneOf?: McpToolSchemaProperty[]
  properties?: Record<string, McpToolSchemaProperty>
  required?: string[]
}

export interface McpToolInputSchema {
  type?: string
  properties?: Record<string, McpToolSchemaProperty>
  required?: string[]
}

export interface McpTool {
  id: string
  name: string
  key: string
  description: string
  inputSchema?: McpToolInputSchema
  enabled: boolean
}

export interface McpServer {
  id: string
  name: string
  description: string
  transport: McpTransport
  endpoint: string
  headers: string
  env: string
  timeoutSeconds: number
  qpm: number
  maxConcurrency: number
  enabled: boolean
  connection: ConnectionStatus
  tools: McpTool[]
  callCount: number
  successRate: number
  lastTestedAt?: string
  createdAt: string
  updatedAt: string
}

export interface SkillVersion {
  version: string
  fileSize: string
  createdAt: string
  note: string
}

export interface Skill {
  id: string
  name: string
  description: string
  instructions: string
  version: string
  fileName: string
  fileSize: string
  sizeBytes: number
  referencedBy: number
  verified: boolean
  versions: SkillVersion[]
  createdAt: string
  updatedAt: string
}

export interface BuiltinTool {
  id: string
  name: string
  key: string
  description: string
  version: string
  enabled: boolean
  callCount: number
}

export interface ModelPublication {
  id: string
  modelId: string
  version: string
  callIdentifier: string
  credentialSuffix: string
  headersConfigured: boolean
  temperature: number
  maxOutputTokens: number
  qpm: number
  enabled: boolean
  createdAt: string
}

export interface ModelResource {
  id: string
  name: string
  upstreamModelId: string
  type: ModelType
  vendor: string
  baseUrl: string
  credentialSuffix: string
  headers: string
  timeoutSeconds: number
  note: string
  enabled: boolean
  connection: ConnectionStatus
  latencyMs: number
  lastTestedAt?: string
  createdAt: string
  updatedAt: string
}

export interface AgentMounts {
  mcpIds: string[]
  skillIds: string[]
  toolIds: string[]
  modelIds: string[]
}

export interface AgentAdvanced {
  keepContext: boolean
  autoRetry: boolean
  outputThinkingSummary: boolean
  maxToolCalls: number
  maxRunSeconds: number
}

export interface Agent {
  id: string
  name: string
  description: string
  theme: AgentTheme
  defaultModelId: string | null
  testModelId: string | null
  systemPrompt: string
  variables: string[]
  modelParams: { temperature: number; topP: number; maxOutputTokens: number }
  mounts: AgentMounts
  advanced: AgentAdvanced
  apiEnabled: boolean
  aguiEnabled: boolean
  credentialSuffix: string
  sessionCount: number
  createdAt: string
  updatedAt: string
}

export interface TokenUsage {
  inputTokens: number
  outputTokens: number
  totalTokens: number
}

export interface LogEntry {
  logId: string
  timestamp: string
  type: LogType
  status: LogStatus
  source: LogSource
  name: string
  summary: string
  runId?: string
  threadId?: string
  agentId?: string
  resourceId?: string
  durationMs?: number
  inputSummary?: string
  outputSummary?: string
  tokenUsage?: TokenUsage
  errorCode?: string
  errorMessage?: string
  credentialSuffix?: string
}

export interface AgentHistory {
  items: LogEntry[]
  total: number
  sources: LogSource[]
}

export interface NotificationItem {
  id: string
  kind: 'error' | 'info' | 'success'
  title: string
  summary: string
  timestamp: string
  targetPath: string
  read: boolean
}

export interface WorkspaceSettings {
  workspaceName: string
  language: 'zh-CN' | 'en-US'
  timezone: string
  domain: string
  telemetry: boolean
  autoArchive: boolean
  retentionDays: number
  accessKey: string
  accessKeySuffix: string
}

export interface ChatToolCall {
  name: string
  args: string
  status: 'success' | 'failed'
  result: string
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'tool'
  content: string
  timestamp: string
  toolCall?: ChatToolCall
  /** 仅前端本地状态：该条消息仍在接收增量 token。 */
  streaming?: boolean
  /** 仅前端本地状态：推理模型下发的思考过程，不参与落库。 */
  thinking?: string
}

export interface TrendPoint {
  date: string
  label: string
  count: number
}

export interface ExportTask {
  id: string
  createdAt: string
  format: 'csv' | 'json'
  filters: string
  recordCount: number
  fileSizeBytes: number
  expiresAt: string
  status: 'pending' | 'completed' | 'expired'
}

export interface SearchResultLike {
  id: string
  name: string
  kind: 'agent' | 'model'
  subtitle: string
  path: string
}

export interface ConfigOption {
  value: string
  label: string
  days?: number
}

export interface SystemLimits {
  [key: string]: number
  agentNameMaxLength: number
  promptMaxChars: number
  promptMaxVariables: number
  temperatureMin: number
  temperatureMax: number
  topPMin: number
  topPMax: number
  maxOutputTokensCeiling: number
  maxToolCallsMin: number
  maxToolCallsMax: number
  maxRunSecondsMin: number
  maxRunSecondsMax: number
  qpmMin: number
  accessKeyMinLength: number
  skillMaxBytes: number
  mcpTimeoutMin: number
  mcpTimeoutMax: number
  exportExpireDays: number
  logPageSize: number
  searchMaxResults: number
  activityMaxItems: number
  chatTimeoutMs: number
  httpRequestTimeoutMs: number
}

export interface SystemConfig {
  platform: {
    name: string
    version: string
    domain: string
    defaultWorkspaceName: string
    defaultLanguage: string
    defaultTimezone: string
    defaultRetentionDays: number
  }
  options: {
    agentThemes: ConfigOption[]
    modelTypes: ConfigOption[]
    publishableModelTypes: string[]
    mcpTransports: ConfigOption[]
    logTypes: ConfigOption[]
    logStatuses: ConfigOption[]
    logSources: ConfigOption[]
    languages: ConfigOption[]
    timezones: ConfigOption[]
    retentionDays: number[]
    logRanges: ConfigOption[]
    historyRanges: ConfigOption[]
    trendRanges: ConfigOption[]
    protocols: ConfigOption[]
    sampleLanguages: string[]
    exportFormats: ('csv' | 'json')[]
  }
  defaults: {
    agent: {
      description: string
      systemPrompt: string
      theme: AgentTheme
      modelParams: { temperature: number; topP: number; maxOutputTokens: number }
      advanced: AgentAdvanced
    }
    model: { type: ModelType; vendor: string; baseUrl: string; headers: string; timeoutSeconds: number; enabled: boolean }
    mcp: {
      transport: McpTransport
      endpoint: string
      description: string
      headers: string
      env: string
      timeoutSeconds: number
      qpm: number
      maxConcurrency: number
    }
    publication: { temperature: number; maxOutputTokens: number; qpm: number }
    skill: { firstVersion: string; firstNote: string }
  }
  limits: SystemLimits
  runtime: Record<string, unknown>
  builtinTools: { key: string; name: string; description: string; version: string }[]
}

export interface OverviewStats {
  agentsTotal: number
  agentsPublished: number
  todayCalls: number
  callsChangePercent: number
  modelsTotal: number
  modelsEnabled: number
  successRate: number
  avgDurationMs: number
  avgDurationChange: number
  abnormalCount: number
  typeCounts: Record<string, number>
}

export interface ResourceHealth {
  modelsEnabled: number
  modelsTotal: number
  modelLatencyMs: number
  mcpEnabled: number
  mcpTotal: number
  availableTools: number
  skillsTotal: number
  skillsLatest: string
  toolsEnabled: number
  toolsTotal: number
  toolsTodayCalls: number
  abnormalCount: number
}

export interface SendResult {
  messages: ChatMessage[]
  runId: string
  threadId: string
}

export interface AgentDraft {
  name: string
  description: string
  defaultModelId: string
  systemPrompt: string
}

export interface McpDraft {
  name: string
  description: string
  transport: McpTransport
  endpoint: string
  headers: string
  env: string
  timeoutSeconds: number
  tested: boolean
}

export interface DiscoveredMcpTool {
  name: string
  key: string
  description: string
}

export interface McpDiscoveryResult {
  toolCount: number
  tools: DiscoveredMcpTool[]
  latencyMs: number
}

export interface McpToolTestResult {
  toolKey: string
  status: 'success' | 'failed'
  durationMs: number
  output: string
  error?: string | null
  timestamp: string
  log?: LogEntry | null
}

export interface SkillDraft {
  name: string
  instructions: string
  fileName: string
  sizeBytes: number
  file?: File
}

export interface AccessKeyDraft {
  current: string
  next: string
  confirm: string
}

export interface WorkspaceData {
  systemConfig: SystemConfig
  agents: Agent[]
  models: ModelResource[]
  publications: ModelPublication[]
  mcps: McpServer[]
  skills: Skill[]
  tools: BuiltinTool[]
  logs: LogEntry[]
  notifications: NotificationItem[]
  settings: WorkspaceSettings
  chatSessions: Record<string, ChatMessage[]>
  trends: { d14: TrendPoint[]; d30: TrendPoint[] }
  exportTasks: ExportTask[]
}

export interface PublicationDraft {
  version: string
  callIdentifier: string
  apiKey: string
  headers: string
  temperature: number
  maxOutputTokens: number
  qpm: number
}

export interface ModelDraft {
  name: string
  upstreamModelId: string
  type: ModelType
  vendor: string
  baseUrl: string
  apiKey: string
  headers: string
  timeoutSeconds: number
  note: string
  enabled: boolean
}
