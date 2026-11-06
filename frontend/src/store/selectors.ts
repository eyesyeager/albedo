import type {
  Agent,
  AgentMounts,
  LogEntry,
  LogSource,
  LogStatus,
  LogType,
  ModelResource,
  ResourceHealth,
  WorkspaceData,
} from '../types/domain'
import { formatRelative, isToday } from '../lib/format'
import { validateNumber } from '../lib/validate'

export function isAgentPublished(agent: Agent): boolean {
  return agent.apiEnabled || agent.aguiEnabled
}

export function enabledChatModels(models: ModelResource[]): ModelResource[] {
  return models.filter((model) => model.enabled && model.type === 'chat')
}

export function usableChatModels(models: ModelResource[]): ModelResource[] {
  return enabledChatModels(models).filter((model) => model.connection === 'normal')
}

export function publicationsOf(data: WorkspaceData, modelId: string) {
  return data.publications.filter((publication) => publication.modelId === modelId)
}

export function activePublicationCount(data: WorkspaceData, modelId: string): number {
  return publicationsOf(data, modelId).filter((publication) => publication.enabled).length
}

export function agentSessions(data: Pick<WorkspaceData, 'logs'>, agentId: string): LogEntry[] {
  return data.logs.filter((log) => log.type === 'session' && log.agentId === agentId)
}

export function executionChain(logs: LogEntry[], runId?: string): LogEntry[] {
  if (!runId) return []
  return logs
    .filter((log) => log.runId === runId)
    .sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime())
}

const MOUNT_KEYS: Record<'model' | 'mcp' | 'tool' | 'skill', keyof AgentMounts> = {
  model: 'modelIds',
  mcp: 'mcpIds',
  tool: 'toolIds',
  skill: 'skillIds',
}

export function affectedAgents(data: WorkspaceData, kind: 'model' | 'mcp' | 'tool' | 'skill', resourceId: string): Agent[] {
  const key = MOUNT_KEYS[kind]
  return data.agents.filter((agent) => agent.mounts[key].includes(resourceId))
}

export function mountIssues(data: WorkspaceData, agent: Agent): string[] {
  const issues: string[] = []
  const checkExists = (id: string, list: { id: string; name?: string }[], label: string) => {
    if (!list.some((item) => item.id === id)) issues.push(`${label}资源已丢失（${id}）`)
  }
  agent.mounts.modelIds.forEach((id) => {
    const model = data.models.find((item) => item.id === id)
    if (!model) issues.push(`模型资源已丢失（${id}）`)
    else if (!model.enabled) issues.push(`模型「${model.name}」已停用`)
    else if (model.connection !== 'normal') issues.push(`模型「${model.name}」连接状态异常`)
  })
  agent.mounts.mcpIds.forEach((id) => {
    const mcp = data.mcps.find((item) => item.id === id)
    if (!mcp) issues.push(`MCP 资源已丢失（${id}）`)
    else if (!mcp.enabled) issues.push(`MCP「${mcp.name}」已停用`)
  })
  agent.mounts.skillIds.forEach((id) => checkExists(id, data.skills, 'Skill'))
  agent.mounts.toolIds.forEach((id) => {
    const tool = data.tools.find((item) => item.id === id)
    if (!tool) issues.push(`内置工具资源已丢失（${id}）`)
    else if (!tool.enabled) issues.push(`内置工具「${tool.name}」已停用`)
  })
  return issues
}

export function publishChecks(data: WorkspaceData, agent: Agent): string[] {
  const issues: string[] = []
  if (!agent.name.trim()) issues.push('智能体名称未配置')
  if (!agent.systemPrompt.trim()) issues.push('System Prompt 不能为空')
  const model = data.models.find((item) => item.id === agent.defaultModelId)
  if (!model) issues.push('默认模型未配置')
  else if (!model.enabled) issues.push(`默认模型「${model.name}」已停用`)
  else if (model.connection !== 'normal') issues.push(`默认模型「${model.name}」连接状态异常，请重新测试连接`)
  issues.push(...mountIssues(data, agent))
  const limits = data.systemConfig.limits
  const toolLimit = validateNumber(agent.advanced.maxToolCalls, limits.maxToolCallsMin || 1, limits.maxToolCallsMax || 100, '最大工具调用次数')
  if (toolLimit) issues.push(toolLimit)
  const runLimit = validateNumber(agent.advanced.maxRunSeconds, limits.maxRunSecondsMin || 3, limits.maxRunSecondsMax || 3600, '单次运行最大时长')
  if (runLimit) issues.push(runLimit)
  return issues
}

/** 资源健康度由工作区真实数据派生，指标与后端统计接口保持一致口径。 */
export function getResourceHealth(data: WorkspaceData): ResourceHealth {
  const enabledModels = data.models.filter((model) => model.enabled)
  const enabledMcps = data.mcps.filter((mcp) => mcp.enabled)
  const enabledTools = data.tools.filter((tool) => tool.enabled)
  const availableTools = enabledMcps.reduce((sum, mcp) => sum + mcp.tools.filter((tool) => tool.enabled).length, 0)
  const latestSkill = [...data.skills].sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())[0]
  const abnormalCount = [...enabledModels, ...enabledMcps].filter((resource) => resource.connection !== 'normal').length
  return {
    modelsEnabled: enabledModels.length,
    modelsTotal: data.models.length,
    modelLatencyMs: enabledModels.length
      ? Math.round(enabledModels.reduce((sum, model) => sum + model.latencyMs, 0) / enabledModels.length)
      : 0,
    mcpEnabled: enabledMcps.length,
    mcpTotal: data.mcps.length,
    availableTools,
    skillsTotal: data.skills.length,
    skillsLatest: latestSkill ? formatRelative(latestSkill.updatedAt) : '暂无更新',
    toolsEnabled: enabledTools.length,
    toolsTotal: data.tools.length,
    toolsTodayCalls: data.logs.filter((log) => log.type === 'tool' && isToday(log.timestamp)).length,
    abnormalCount,
  }
}

export interface LogFilters {
  keyword: string
  type: LogType | 'all'
  status: LogStatus | 'all'
  range: 'today' | '7d' | '30d' | '1y'
  agentId: string
}

export const DEFAULT_LOG_FILTERS: LogFilters = {
  keyword: '',
  type: 'all',
  status: 'all',
  range: '7d',
  agentId: 'all',
}

export const LOG_TYPE_LABEL: Record<LogType, string> = {
  session: '会话',
  mcp: 'MCP',
  skill: 'Skill',
  tool: '内置工具',
  model: '模型',
  system: '系统操作',
}

export const LOG_SOURCE_LABEL: Record<LogSource, string> = {
  test_session: '测试会话',
  standard_api: '标准 API',
  agui: 'AGUI',
  model_api: '模型发布 API',
  system: '系统操作',
}
