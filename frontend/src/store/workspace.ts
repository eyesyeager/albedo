import { create } from 'zustand'
import { emptyWorkspace } from '../lib/defaults'
import type {
  Agent,
  BuiltinTool,
  ChatMessage,
  ExportTask,
  LogEntry,
  McpServer,
  McpToolInputSchema,
  ModelPublication,
  ModelResource,
  Skill,
  SystemConfig,
  WorkspaceData,
  WorkspaceSettings,
} from '../types/domain'

interface WorkspaceStore extends WorkspaceData {
  replaceAll: (data: WorkspaceData) => void
  patchSystemConfig: (config: SystemConfig) => void
  upsertAgent: (agent: Agent) => void
  patchAgent: (id: string, patch: Partial<Agent>) => void
  removeAgent: (id: string) => void
  upsertModel: (model: ModelResource) => void
  patchModel: (id: string, patch: Partial<ModelResource>) => void
  removeModel: (id: string) => void
  upsertPublication: (publication: ModelPublication) => void
  patchPublication: (id: string, patch: Partial<ModelPublication>) => void
  removePublication: (id: string) => void
  upsertMcp: (mcp: McpServer) => void
  patchMcp: (id: string, patch: Partial<McpServer>) => void
  removeMcp: (id: string) => void
  patchMcpTool: (mcpId: string, toolId: string, enabled: boolean) => void
  patchMcpToolSchema: (mcpId: string, toolId: string, inputSchema: McpToolInputSchema) => void
  addSkill: (skill: Skill) => void
  patchSkill: (id: string, patch: Partial<Skill>) => void
  removeSkill: (id: string) => void
  patchTool: (id: string, patch: Partial<BuiltinTool>) => void
  appendLogs: (entries: LogEntry[]) => void
  addExportTask: (task: ExportTask) => void
  patchSettings: (patch: Partial<WorkspaceSettings>) => void
  markAllNotificationsRead: () => void
  appendChatMessages: (agentId: string, messages: ChatMessage[]) => void
  appendChatMessage: (agentId: string, message: ChatMessage) => void
  patchChatMessage: (agentId: string, id: string, patch: Partial<ChatMessage>) => void
  appendChatDelta: (agentId: string, id: string, delta: string) => void
  appendChatThinking: (agentId: string, id: string, delta: string) => void
  commitChatRun: (agentId: string, dropIds: string[], messages: ChatMessage[]) => void
  clearChat: (agentId: string) => void
}

const byTimeDesc = (a: LogEntry, b: LogEntry): number =>
  new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()

function updateById<T extends { id: string }>(list: T[], id: string, patch: Partial<T>): T[] {
  return list.map((item) => (item.id === id ? { ...item, ...patch } : item))
}

export const useWorkspace = create<WorkspaceStore>()((set) => ({
  ...emptyWorkspace(),
  replaceAll: (data) => set({ ...data }),
  patchSystemConfig: (config) => set({ systemConfig: config }),
  upsertAgent: (agent) =>
    set((state) => ({
      agents: state.agents.some((item) => item.id === agent.id)
        ? updateById(state.agents, agent.id, agent)
        : [agent, ...state.agents],
    })),
  patchAgent: (id, patch) =>
    set((state) => ({ agents: updateById(state.agents, id, { ...patch, updatedAt: new Date().toISOString() }) })),
  removeAgent: (id) =>
    set((state) => {
      const chatSessions = { ...state.chatSessions }
      delete chatSessions[id]
      return { agents: state.agents.filter((item) => item.id !== id), chatSessions }
    }),
  upsertModel: (model) =>
    set((state) => ({
      models: state.models.some((item) => item.id === model.id)
        ? updateById(state.models, model.id, model)
        : [model, ...state.models],
    })),
  patchModel: (id, patch) =>
    set((state) => ({ models: updateById(state.models, id, { ...patch, updatedAt: new Date().toISOString() }) })),
  removeModel: (id) =>
    set((state) => ({
      models: state.models.filter((item) => item.id !== id),
      publications: state.publications.filter((item) => item.modelId !== id),
      agents: state.agents.map((agent) => ({
        ...agent,
        defaultModelId: agent.defaultModelId === id ? null : agent.defaultModelId,
        testModelId: agent.testModelId === id ? null : agent.testModelId,
        mounts: { ...agent.mounts, modelIds: agent.mounts.modelIds.filter((modelId) => modelId !== id) },
      })),
    })),
  upsertPublication: (publication) =>
    set((state) => ({
      publications: state.publications.some((item) => item.id === publication.id)
        ? updateById(state.publications, publication.id, publication)
        : [publication, ...state.publications],
    })),
  patchPublication: (id, patch) =>
    set((state) => ({ publications: updateById(state.publications, id, patch) })),
  removePublication: (id) =>
    set((state) => ({ publications: state.publications.filter((item) => item.id !== id) })),
  upsertMcp: (mcp) =>
    set((state) => ({
      mcps: state.mcps.some((item) => item.id === mcp.id)
        ? updateById(state.mcps, mcp.id, mcp)
        : [mcp, ...state.mcps],
    })),
  patchMcp: (id, patch) =>
    set((state) => ({ mcps: updateById(state.mcps, id, { ...patch, updatedAt: new Date().toISOString() }) })),
  removeMcp: (id) =>
    set((state) => ({
      mcps: state.mcps.filter((item) => item.id !== id),
      agents: state.agents.map((agent) => ({
        ...agent,
        mounts: { ...agent.mounts, mcpIds: agent.mounts.mcpIds.filter((mcpId) => mcpId !== id) },
      })),
    })),
  patchMcpTool: (mcpId, toolId, enabled) =>
    set((state) => ({
      mcps: state.mcps.map((mcp) =>
        mcp.id === mcpId
          ? { ...mcp, tools: mcp.tools.map((tool) => (tool.id === toolId ? { ...tool, enabled } : tool)) }
          : mcp,
      ),
    })),
  patchMcpToolSchema: (mcpId, toolId, inputSchema) =>
    set((state) => ({
      mcps: state.mcps.map((mcp) =>
        mcp.id === mcpId
          ? { ...mcp, tools: mcp.tools.map((tool) => (tool.id === toolId ? { ...tool, inputSchema } : tool)) }
          : mcp,
      ),
    })),
  addSkill: (skill) => set((state) => ({ skills: [skill, ...state.skills] })),
  patchSkill: (id, patch) => set((state) => ({ skills: updateById(state.skills, id, patch) })),
  removeSkill: (id) =>
    set((state) => ({
      skills: state.skills.filter((item) => item.id !== id),
      agents: state.agents.map((agent) => ({
        ...agent,
        mounts: { ...agent.mounts, skillIds: agent.mounts.skillIds.filter((skillId) => skillId !== id) },
      })),
    })),
  patchTool: (id, patch) => set((state) => ({ tools: updateById(state.tools, id, patch) })),
  appendLogs: (entries) =>
    set((state) => ({ logs: [...entries, ...state.logs].sort(byTimeDesc).slice(0, 200) })),
  addExportTask: (task) => set((state) => ({ exportTasks: [task, ...state.exportTasks].slice(0, 20) })),
  patchSettings: (patch) => set((state) => ({ settings: { ...state.settings, ...patch } })),
  markAllNotificationsRead: () =>
    set((state) => ({ notifications: state.notifications.map((item) => ({ ...item, read: true })) })),
  appendChatMessages: (agentId, messages) =>
    set((state) => ({
      chatSessions: { ...state.chatSessions, [agentId]: [...(state.chatSessions[agentId] ?? []), ...messages] },
    })),
  appendChatMessage: (agentId, message) =>
    set((state) => ({
      chatSessions: { ...state.chatSessions, [agentId]: [...(state.chatSessions[agentId] ?? []), message] },
    })),
  patchChatMessage: (agentId, id, patch) =>
    set((state) => ({
      chatSessions: { ...state.chatSessions, [agentId]: updateById(state.chatSessions[agentId] ?? [], id, patch) },
    })),
  appendChatDelta: (agentId, id, delta) =>
    set((state) => ({
      chatSessions: {
        ...state.chatSessions,
        [agentId]: (state.chatSessions[agentId] ?? []).map((message) =>
          message.id === id ? { ...message, content: message.content + delta } : message,
        ),
      },
    })),
  appendChatThinking: (agentId, id, delta) =>
    set((state) => ({
      chatSessions: {
        ...state.chatSessions,
        [agentId]: (state.chatSessions[agentId] ?? []).map((message) =>
          message.id === id ? { ...message, thinking: (message.thinking ?? '') + delta } : message,
        ),
      },
    })),
  commitChatRun: (agentId, dropIds, messages) =>
    set((state) => {
      const dropped = new Set(dropIds)
      const kept = (state.chatSessions[agentId] ?? []).filter((message) => !dropped.has(message.id))
      return { chatSessions: { ...state.chatSessions, [agentId]: [...kept, ...messages] } }
    }),
  clearChat: (agentId) => set((state) => ({ chatSessions: { ...state.chatSessions, [agentId]: [] } })),
}))
