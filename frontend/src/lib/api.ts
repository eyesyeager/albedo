import type {
  AccessKeyDraft,
  Agent,
  AgentDraft,
  AgentHistory,
  BuiltinTool,
  ChatMessage,
  CredentialView,
  ExportTask,
  LogEntry,
  McpDiscoveryResult,
  McpDraft,
  McpServer,
  McpToolInputSchema,
  McpToolTestResult,
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
import { ApiError } from './errors'
import { http, httpBlob, hydrate, jsonBody, openEventStream, replaceFromServer, fetchSession, fetchBootstrap, clearSessionState } from './http'
import { readSse } from './sse'

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
  toolCount?: number
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
      // 登录做成事务：快照加载成功后才置登录态，失败时整体保持未登录。
      await replaceFromServer()
      useAuth.getState().setAuthenticated(true)
      syncLog(response.log)
    },
    async logout(): Promise<void> {
      // 先同步清空全局工作区，网络失败也不残留上一会话的密钥后缀、日志与聊天记录。
      clearSessionState()
      try {
        await post<{ ok: boolean; log?: LogEntry }>('/auth/logout')
      } catch (error) {
        console.warn('[albedo] 退出登录请求失败，本地会话已清除：', error)
      }
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
    async revealCredential(id: string): Promise<CredentialView> {
      return await http<CredentialView>(`/agents/${encodeURIComponent(id)}/credential`)
    },
    async setProtocol(id: string, protocol: 'api' | 'agui', enabled: boolean): Promise<void> {
      const response = await patch<WriteResponse<Agent>>(`/agents/${id}/protocols`, { protocol, enabled })
      if (response.entity) state().upsertAgent(response.entity)
      syncLog(response.log)
    },
    // 服务端分页外的会话历史，避免只依赖 bootstrap 的 50 条日志快照。
    async history(id: string): Promise<AgentHistory> {
      return await http<AgentHistory>(`/agents/${encodeURIComponent(id)}/history`)
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
    async revealCredential(id: string): Promise<CredentialView> {
      return await http<CredentialView>(`/models/${encodeURIComponent(id)}/credential`)
    },
    async revealPublicationKey(id: string): Promise<CredentialView> {
      return await http<CredentialView>(`/publications/${encodeURIComponent(id)}/credential`)
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
    async test(draft: McpDraft): Promise<McpDiscoveryResult> {
      // 后端工具发现最长 30 秒，默认 15 秒的请求超时会被提前中断。
      return await post<McpDiscoveryResult>('/mcps/test', draft, { timeoutMs: 40000 })
    },
    async testExisting(id: string): Promise<{ toolCount: number; latencyMs: number }> {
      const response = await post<WriteResponse<McpServer>>(`/mcps/${id}/test`, undefined, { timeoutMs: 40000 })
      if (response.entity) state().upsertMcp(response.entity)
      syncLog(response.log)
      return { toolCount: response.toolCount ?? 0, latencyMs: response.latencyMs ?? 0 }
    },
    async testTool(mcpId: string, toolId: string, args: Record<string, unknown>, timeoutSeconds: number): Promise<McpToolTestResult> {
      const result = await post<McpToolTestResult>(
        `/mcps/${mcpId}/tools/${toolId}/test`,
        { arguments: args, timeoutSeconds },
        { timeoutMs: Math.max(20000, timeoutSeconds * 1000 + 5000) },
      )
      syncLog(result.log ?? undefined)
      const current = state().mcps.find((item) => item.id === mcpId)
      if (current) {
        const total = current.callCount + 1
        const succeeded = (current.successRate / 100) * current.callCount + (result.status === 'success' ? 1 : 0)
        state().patchMcp(mcpId, { callCount: total, successRate: Math.round((succeeded / total) * 10000) / 100 })
      }
      return result
    },
    async toolSchema(mcpId: string, toolId: string): Promise<McpToolInputSchema> {
      const result = await post<{ toolKey: string; inputSchema: McpToolInputSchema }>(
        `/mcps/${mcpId}/tools/${toolId}/schema`,
        undefined,
        { timeoutMs: 40000 },
      )
      state().patchMcpToolSchema(mcpId, toolId, result.inputSchema)
      return result.inputSchema
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
    async remove(id: string): Promise<void> {
      const response = await del<WriteResponse<never>>(`/mcps/${id}`)
      state().removeMcp(id)
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
    async remove(skillId: string): Promise<void> {
      const response = await del<WriteResponse<never>>(`/skills/${skillId}`)
      state().removeSkill(skillId)
      syncLog(response.log)
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
      // 服务端已返回最新 settings；不再全量重拉快照，避免较慢的旧快照覆盖期间的局部写操作。
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
    /** 流式测试会话：先本地占位渲染增量 token，run.finished 时用服务端权威消息替换。 */
    async stream(agentId: string, content: string, modelId: string, signal?: AbortSignal): Promise<SendResult> {
      const stamp = (): string => new Date().toISOString()
      const pendingIds: string[] = []
      const userId = `tmp-user-${Date.now()}`
      pendingIds.push(userId)
      state().appendChatMessage(agentId, { id: userId, role: 'user', content, timestamp: stamp() })

      let currentId: string | null = null
      let seq = 0
      let thinking = ''
      let result: SendResult = { messages: [], runId: '', threadId: '' }
      let failure: ApiError | null = null
      let finished = false
      const toolBubbles = new Map<string, { id: string; name: string; args: string }>()

      const openBubble = (): void => {
        seq += 1
        currentId = `tmp-assistant-${Date.now()}-${seq}`
        pendingIds.push(currentId)
        state().appendChatMessage(agentId, {
          id: currentId, role: 'assistant', content: '', timestamp: stamp(), streaming: true,
        })
      }
      const closeBubble = (): void => {
        if (currentId) state().patchChatMessage(agentId, currentId, { streaming: false })
        currentId = null
      }

      // 推理模型可能先输出数分钟思考流，整体超时必须覆盖智能体的 maxRunSeconds，
      // 否则默认 chatTimeoutMs（150s）会在正式回答出现前把流掐断。
      const maxRunSeconds = state().agents.find((item) => item.id === agentId)?.advanced.maxRunSeconds ?? 0
      const timeoutMs = Math.max(limits().chatTimeoutMs || 150000, maxRunSeconds > 0 ? (maxRunSeconds + 30) * 1000 : 0)
      const handle = await openEventStream(`/agents/${agentId}/test-runs`, {
        method: 'POST',
        headers: { Accept: 'text/event-stream' },
        body: jsonBody({ content, testModelId: modelId }),
        timeoutMs,
        signal,
      })

      let streamError: unknown = null
      try {
        await readSse(handle.reader, ({ event, data }) => {
          let payload: Record<string, unknown> = {}
          if (data) {
            try {
              payload = JSON.parse(data) as Record<string, unknown>
            } catch {
              console.warn('[albedo] 忽略格式非法的 SSE 帧：', event, data.slice(0, 120))
              return
            }
          }
          if (event === 'run.started') {
            result = { ...result, runId: String(payload.runId ?? ''), threadId: String(payload.threadId ?? '') }
          } else if (event === 'message.thinking') {
            if (!currentId) openBubble()
            const delta = String(payload.delta ?? '')
            thinking += delta
            if (currentId) state().appendChatThinking(agentId, currentId, delta)
          } else if (event === 'message.start') {
            if (!currentId) openBubble()
          } else if (event === 'message.delta') {
            if (!currentId) openBubble()
            if (currentId) state().appendChatDelta(agentId, currentId, String(payload.delta ?? ''))
          } else if (event === 'message.end') {
            closeBubble()
          } else if (event === 'tool.start') {
            closeBubble()
            const callId = String(payload.toolCallId ?? `seq-${seq}`)
            const name = String(payload.name ?? '工具')
            const args = JSON.stringify(payload.args ?? {}, null, 2)
            const bubbleId = `tmp-tool-${callId}`
            pendingIds.push(bubbleId)
            toolBubbles.set(callId, { id: bubbleId, name, args })
            state().appendChatMessage(agentId, {
              id: bubbleId,
              role: 'tool',
              content: '',
              timestamp: stamp(),
              toolCall: { name, args, status: 'success', result: '执行中…' },
            })
          } else if (event === 'tool.result') {
            const callId = String(payload.toolCallId ?? '')
            const bubble = toolBubbles.get(callId)
            if (bubble) {
              state().patchChatMessage(agentId, bubble.id, {
                toolCall: {
                  name: bubble.name,
                  args: bubble.args,
                  status: payload.status === 'failed' ? 'failed' : 'success',
                  result: String(payload.summary ?? ''),
                },
              })
            }
          } else if (event === 'run.finished') {
            finished = true
            closeBubble()
            const messages = (payload.messages as ChatMessage[] | undefined) ?? []
            if (thinking) {
              for (let index = messages.length - 1; index >= 0; index -= 1) {
                if (messages[index].role === 'assistant') {
                  messages[index] = { ...messages[index], thinking }
                  break
                }
              }
            }
            state().commitChatRun(agentId, pendingIds, messages)
            syncLogs(payload.logs as LogEntry[] | undefined)
            if (typeof payload.sessionCount === 'number') {
              state().patchAgent(agentId, { sessionCount: payload.sessionCount })
            }
            result = {
              messages,
              runId: String(payload.runId ?? result.runId),
              threadId: String(payload.threadId ?? result.threadId),
            }
            if (payload.finishReason === 'timeout') {
              failure = new ApiError('run_timeout', '本次运行超过最大时长限制，已终止')
            }
          } else if (event === 'message.error') {
            failure = new ApiError(String(payload.code ?? 'agent_run_failed'), String(payload.message ?? '运行失败'))
          }
        })
      } catch (reason) {
        streamError = reason
      } finally {
        handle.close()
      }

      if (!finished) {
        closeBubble()
        // 流中断 / 超时 / 未收到终态：删除所有本地占位气泡，避免 tmp-* 永久残留。
        state().dropChatMessages(agentId, pendingIds)
      }
      if (streamError) {
        if (streamError instanceof ApiError) throw streamError
        if (streamError instanceof DOMException && streamError.name === 'AbortError') {
          throw new ApiError('upstream_timeout', '等待模型响应超时，请稍后重试')
        }
        throw new ApiError('stream_interrupted', '流式连接中断，请重试')
      }
      if (!finished) throw failure ?? new ApiError('stream_interrupted', '未收到完整运行结果，请重试')
      if (failure) throw failure
      return result
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
