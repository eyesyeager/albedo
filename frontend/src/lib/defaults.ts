import type { SystemConfig, WorkspaceData } from '../types/domain'

/** 启动前的技术性空壳；真实取值一律由 /api/v1/workspace/bootstrap 下发的 systemConfig 覆盖。 */
export function defaultSystemConfig(): SystemConfig {
  return {
    platform: {
      name: 'Albedo', version: '', domain: '', defaultWorkspaceName: '',
      defaultLanguage: 'zh-CN', defaultTimezone: '', defaultRetentionDays: 90,
    },
    options: {
      agentThemes: [], modelTypes: [], publishableModelTypes: [], mcpTransports: [],
      logTypes: [], logStatuses: [], logSources: [], languages: [], timezones: [],
      retentionDays: [], logRanges: [], historyRanges: [], trendRanges: [],
      protocols: [], sampleLanguages: [], exportFormats: [],
    },
    defaults: {
      agent: {
        description: '', systemPrompt: '', theme: 'violet',
        modelParams: { temperature: 0, topP: 0, maxOutputTokens: 0 },
        advanced: { keepContext: true, autoRetry: true, outputThinkingSummary: false, maxToolCalls: 0, maxRunSeconds: 0 },
      },
      model: { type: 'chat', vendor: '', baseUrl: '', headers: '', timeoutSeconds: 0, enabled: true },
      mcp: { transport: 'streamable_http', endpoint: '', description: '', headers: '', timeoutSeconds: 0, qpm: 0, maxConcurrency: 0 },
      publication: { temperature: 0, maxOutputTokens: 0, qpm: 0 },
      skill: { firstVersion: '', firstNote: '' },
    },
    limits: {
      agentNameMaxLength: 0, promptMaxChars: 0, promptMaxVariables: 0,
      temperatureMin: 0, temperatureMax: 0, topPMin: 0, topPMax: 0,
      maxOutputTokensCeiling: 0, maxToolCallsMin: 0, maxToolCallsMax: 0,
      maxRunSecondsMin: 0, maxRunSecondsMax: 0, qpmMin: 0,
      accessKeyMinLength: 0, skillMaxBytes: 0, mcpTimeoutMin: 0, mcpTimeoutMax: 0,
      exportExpireDays: 0, logPageSize: 0, searchMaxResults: 0,
      activityMaxItems: 0, chatTimeoutMs: 0, httpRequestTimeoutMs: 0,
    },
    runtime: {},
    builtinTools: [],
  }
}

export function emptyWorkspace(): WorkspaceData {
  return {
    systemConfig: defaultSystemConfig(),
    agents: [],
    models: [],
    publications: [],
    mcps: [],
    skills: [],
    tools: [],
    logs: [],
    notifications: [],
    settings: {
      workspaceName: '', language: 'zh-CN', timezone: '', domain: '',
      telemetry: true, autoArchive: true, retentionDays: 90, accessKey: '', accessKeySuffix: '',
    },
    chatSessions: {},
    trends: { d14: [], d30: [] },
    exportTasks: [],
  }
}
