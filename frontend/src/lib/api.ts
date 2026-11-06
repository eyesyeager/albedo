import type {
  AccessKeyDraft,
  Agent,
  AgentDraft,
  BuiltinTool,
  ChatMessage,
  ExportTask,
  LogEntry,
  McpDraft,
  McpServer,
  ModelDraft,
  ModelPublication,
  ModelResource,
  NotificationItem,
  OverviewStats,
  PublicationDraft,
  SearchResultLike,
  SendResult,
  Skill,
  SkillDraft,
  SystemConfig,
  TrendPoint,
  WorkspaceData,
  WorkspaceSettings,
} from '../types/domain'
import { useAuth } from '../store/auth'
import { useWorkspace } from '../store/workspace'
import { http, httpBlob, hydrate, jsonBody, replaceFromServer, fetchSession, fetchBootstrap } from './http'

interface WriteResponse<T> {
  entity?: T
  log?: LogEntry
  settings?: WorkspaceSettings
  fullKey?: string
  credentialSuffix?: string
  publication?: ModelPublication
  notifications?: NotificationItem[]
  logs?: LogEntry[]
  latencyMs?: number
}

const state = () => useWorkspace.getState()
const limits = () => state().systemConfig.limits

function syncLog(log?: LogEntry): void {
  if (log) state().appendLogs([log])
}

function syncLogs(logs?: LogEntry[]): void {
  if (logs?.length) state().appendLogs(logs)
}

function post<T>(path: string, body?: unknown, options: { timeoutMs?: number } = {}): Promise<T> {
  return http<T>(path, { ...options, method: 'POST', body: body === undefined ? undefined : jsonBody(body) })
}

function patch<T>(path: string, body?: unknown): Promise<T> {
  return http<T>(path, { method: 'PATCH', body: body === undefined ? undefined : jsonBody(body) })
}

function del<T>(path: string): Promise<T> {
  return http<T>(path, { method: 'DELETE' })
}

function skillForm(draft: SkillDraft): FormData {
  if (!draft.file) throw new Error('未选择 Skill 包，请重新选择 .zip 文件后上传')
  const form = new FormData()
  form.append('name', draft.name)
  form.append('instructions', draft.instructions)
  form.append('fileName', draft.fileName)
  form.append('sizeBytes', String(draft.file.size))
  form.append('file', draft.file, draft.fileName)
  return form
}

function downloadBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

const wait = (ms: number): Promise<void> => new Promise((resolve) => window.setTimeout(resolve, ms))

export const api = {
  auth: {
    async login(key: string): Promise<void> {
      const response = await post<{ authenticated: boolean; log?: LogEntry }>('/auth/login', { key })
      useAuth.getState().setAuthenticated(true)
      syncLog(response.log)
      await replaceFromServer()
    },
    async logout(): Promise<void> {
      const response = await post<{ ok: boolean; log?: LogEntry }>('/auth/logout')
      useAuth.getState().logout()
      syncLog(response.log)
    },
  },

  agents: {
    async create(draft: AgentDraft): Promise<Agent> {
      const response = await post<WriteResponse<Agent>>('/agents', draft)
      const agent = response.entity as Agent
      state().upsertAgent(agent)
      syncLog(response.log)
      return agent
    },
    async update(id: string, changes: Partial<Agent>): Promise<void> {
      const response = await patch<WriteResponse<Agent>>(`/agents/${id}`, changes)
      if (response.entity) state().upsertAgent(response.entity)
      syncLog(response.log)
    },
    async remove(id: string): Promise<void> {
      const response = await http<{ log?: LogEntry }>(`/agents/${id}`, { method: 'DELETE' })
      state().removeAgent(id)
      syncLog(response.log)
    },
    async resetCredential(id: string): Promise<string> {
      const response = await post<WriteResponse<Agent>>(`/agents/${id}/credential/reset`)
      if (response.credentialSuffix) state().patchAgent(id, { credentialSuffix: response.credentialSuffix })
      syncLog(response.log)
      if (!response.fullKey) throw new Error('后端未返回完整凭证，请重试')
      return response.fullKey
    },
    async setProtocol(id: string, protocol: 'api' | 'agui', enabled: boolean): Promise<void> {
      const response = await patch<WriteResponse<Agent>>(`/agents/${id}/protocols`, { protocol, enabled })
      if (response.entity) state().upsertAgent(response.entity)
      syncLog(response.log)
    },
  },

  models: {
    async test(draft: ModelDraft): Promise<{ latencyMs: number }> {
      return await post<{ latencyMs: number }>('/models/test', draft)
    },
    async testExisting(id: string): Promise<{ latencyMs: number }> {
      const response = await post<WriteResponse<ModelResource>>(`/models/${id}/test`)
      if (response.entity) state().upsertModel(response.entity)
      syncLog(response.log)
      return { latencyMs: response.latencyMs ?? response.entity?.latencyMs ?? 0 }
    },
    async remove(id: string): Promise<void> {
      const response = await del<WriteResponse<never>>(`/models/${id}`)
      state().removeModel(id)
      syncLog(response.log)
    },
    async create(draft: ModelDraft): Promise<ModelResource> {
      const response = await post<WriteResponse<ModelResource>>('/models', draft)
      const model = response.entity as ModelResource
      state().upsertModel(model)
      syncLog(response.log)
      return model
    },
    async update(id: string, changes: Partial<ModelResource>): Promise<void> {
      const response = await patch<WriteResponse<ModelResource>>(`/models/${id}`, changes)
      if (response.entity) state().upsertModel(response.entity)
      syncLog(response.log)
    },
    async toggle(id: string, enabled: boolean): Promise<void> {
      const response = await patch<WriteResponse<ModelResource>>(`/models/${id}/enabled`, { enabled })
      if (response.entity) state().upsertModel(response.entity)
      syncLog(response.log)
    },
    async publish(modelId: string, draft: PublicationDraft): Promise<{ publication: ModelPublication; fullKey: string }> {
      const response = await post<WriteResponse<ModelPublication>>(`/models/${modelId}/publications`, draft)
      const publication = response.publication as ModelPublication
      state().upsertPublication(publication)
      syncLog(response.log)
      if (!response.fullKey) throw new Error('后端未返回完整凭证，请重试')
      return { publication, fullKey: response.fullKey }
    },
    async togglePublication(id: string, enabled: boolean): Promise<void> {
      const response = await patch<WriteResponse<ModelPublication>>(`/publications/${id}`, { enabled })
      if (response.entity) state().upsertPublication(response.entity)
      syncLog(response.log)
    },
    async rotatePublicationKey(id: string, apiKey: string): Promise<string> {
      const response = await post<WriteResponse<ModelPublication>>(`/publications/${id}/key/rotate`, { apiKey })
      if (response.entity) state().upsertPublication(response.entity)
      syncLog(response.log)
      if (!response.fullKey) throw new Error('后端未返回完整凭证，请重试')
      return response.fullKey
    },
    async removePublication(id: string): Promise<void> {
      const response = await del<WriteResponse<never>>(`/publications/${id}`)
      state().removePublication(id)
      syncLog(response.log)
    },
  },

  mcp: {
    async test(draft: McpDraft): Promise<{ toolCount: number }> {
      return await post<{ toolCount: number }>('/mcps/test', draft)
    },
    async create(draft: McpDraft): Promise<void> {
      const response = await post<WriteResponse<McpServer>>('/mcps', draft)
      if (response.entity) state().upsertMcp(response.entity)
      syncLog(response.log)
    },
    async update(id: string, changes: Partial<McpServer>): Promise<void> {
      const response = await patch<WriteResponse<McpServer>>(`/mcps/${id}`, changes)
      if (response.entity) state().upsertMcp(response.entity)
      syncLog(response.log)
    },
    async toggle(id: string, enabled: boolean): Promise<void> {
      const response = await patch<WriteResponse<McpServer>>(`/mcps/${id}`, { enabled })
      if (response.entity) state().upsertMcp(response.entity)
      syncLog(response.log)
    },
    async toggleTool(mcpId: string, toolId: string, enabled: boolean): Promise<void> {
      const response = await patch<WriteResponse<McpServer>>(`/mcps/${mcpId}/tools/${toolId}`, { enabled })
      if (response.entity) state().upsertMcp(response.entity)
      syncLog(response.log)
    },
    async updateUsage(id: string, qpm: number, maxConcurrency: number): Promise<void> {
      const response = await patch<WriteResponse<McpServer>>(`/mcps/${id}/usage`, { qpm, maxConcurrency })
      if (response.entity) state().upsertMcp(response.entity)
      syncLog(response.log)
    },
  },

  skills: {
    async create(draft: SkillDraft): Promise<Skill> {
      const response = await http<WriteResponse<Skill>>('/skills', { method: 'POST', body: skillForm(draft) })
      const skill = response.entity as Skill
      state().addSkill(skill)
      syncLog(response.log)
      return skill
    },
    async createVersion(skillId: string, draft: SkillDraft): Promise<Skill> {
      const response = await http<WriteResponse<Skill>>(`/skills/${skillId}/versions`, { method: 'POST', body: skillForm(draft) })
      const skill = response.entity as Skill
      state().patchSkill(skillId, skill)
      syncLog(response.log)
      return skill
    },
  },

  tools: {
    async toggle(id: string, enabled: boolean): Promise<void> {
      const response = await patch<WriteResponse<BuiltinTool>>(`/tools/${id}/enabled`, { enabled })
      if (response.entity) state().patchTool(id, response.entity)
      syncLog(response.log)
    },
  },

  settings: {
    async update(changes: Partial<WorkspaceSettings>): Promise<void> {
      const { accessKey: _accessKey, accessKeySuffix: _suffix, ...safe } = changes
      const response = await patch<WriteResponse<never>>('/settings', safe)
      if (response.settings) {
        state().patchSettings({ ...response.settings, accessKey: state().settings.accessKey })
      }
      syncLog(response.log)
      syncLogs(response.logs)
      if (safe.retentionDays !== undefined) await replaceFromServer()
    },
    async changeAccessKey(draft: AccessKeyDraft): Promise<void> {
      const response = await post<WriteResponse<never>>('/settings/access-key', draft)
      if (response.settings) {
        state().patchSettings({ accessKey: '', accessKeySuffix: response.settings.accessKeySuffix })
      }
      syncLog(response.log)
    },
    async getConfigs(): Promise<SystemConfig> {
      return await http<SystemConfig>('/settings/configs')
    },
    async updateConfigs(patchValues: Partial<SystemConfig>): Promise<SystemConfig> {
      const response = await patch<{ config: SystemConfig; log?: LogEntry }>('/settings/configs', patchValues)
      state().patchSystemConfig(response.config)
      syncLog(response.log)
      return response.config
    },
  },

  logs: {
    async export(format: 'csv' | 'json', filtersLabel: string, filters?: Record<string, unknown>): Promise<ExportTask> {
      const created = await post<ExportTask>('/logs/exports', {
        format,
        filters: filters ?? {},
        filtersLabel,
      })
      let result: { task: ExportTask; log?: LogEntry; downloadUrl?: string } | null = null
      const maxAttempts = limits().exportPollMaxAttempts || 20
      const interval = limits().exportPollIntervalMs || 150
      for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
        const status = await http<{ task: ExportTask; log?: LogEntry; downloadUrl?: string }>(`/logs/exports/${created.id}`)
        if (status.task.status === 'completed') {
          result = status
          break
        }
        await wait(interval)
      }
      if (!result) throw new Error('日志导出任务未完成，请稍后在导出记录中重试')
      const downloadPath = result.downloadUrl
        ? result.downloadUrl.replace('/api/v1', '')
        : `/logs/exports/${created.id}/download`
      const blob = await httpBlob(downloadPath)
      downloadBlob(blob, `albedo-logs-${created.id}.${format}`)
      state().addExportTask(result.task)
      syncLog(result.log)
      return result.task
    },
  },

  chat: {
    async send(agentId: string, content: string, modelId: string): Promise<SendResult> {
      const response = await post<SendResult & { logs?: LogEntry[]; sessionCount?: number }>(
        `/agents/${agentId}/test-runs`,
        { content, testModelId: modelId },
        { timeoutMs: limits().chatTimeoutMs || 150000 },
      )
      const messages: ChatMessage[] = response.messages ?? []
      state().appendChatMessages(agentId, messages)
      syncLogs(response.logs)
      if (response.sessionCount !== undefined) state().patchAgent(agentId, { sessionCount: response.sessionCount })
      return { messages, runId: response.runId, threadId: response.threadId }
    },
    async clear(agentId: string): Promise<void> {
      await http<void>(`/agents/${agentId}/test-session`, { method: 'DELETE' })
      state().clearChat(agentId)
    },
  },

  notifications: {
    async readAll(): Promise<void> {
      const response = await post<WriteResponse<never>>('/notifications/read-all')
      if (response.notifications) useWorkspace.setState({ notifications: response.notifications })
      else state().markAllNotificationsRead()
      syncLog(response.log)
    },
  },
}

export const adminApi = {
  bootstrap: fetchBootstrap,
  session: fetchSession,
  hydrate,
  logs: {
    query: (params: Record<string, string | number>): Promise<{ items: LogEntry[]; total: number; page: number; pageSize: number; typeCounts: Record<string, number> }> => {
      const query = new URLSearchParams()
      Object.entries(params).forEach(([key, value]) => query.set(key, String(value)))
      return http(`/logs?${query.toString()}`)
    },
    detail: (logId: string): Promise<LogEntry & { chain: LogEntry[] }> => http(`/logs/${encodeURIComponent(logId)}`),
    exportStatus: (taskId: string): Promise<{ task: ExportTask }> => http(`/logs/exports/${taskId}`),
  },
  stats: {
    overview: (): Promise<OverviewStats> => http('/stats/overview'),
    trends: (days: number): Promise<TrendPoint[]> => http(`/stats/trends?days=${days}`),
  },
  search: (keyword: string): Promise<SearchResultLike[]> => http(`/search?q=${encodeURIComponent(keyword)}`),
  notifications: {
    list: (): Promise<NotificationItem[]> => http('/notifications'),
  },
  resources: {
    impact: (kind: string, id: string): Promise<{ agents: Agent[]; names: string[] }> =>
      http(`/resources/${kind}/${id}/impact`),
  },
}

export type AdminWorkspaceData = WorkspaceData
