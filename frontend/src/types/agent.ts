/**
 * Agent 展示类型（契约：docs/api-spec.md §4.4.1）。
 *
 * 🔴 后端不下发 systemPrompt / providerKey / model / 参数等内部字段，前端也不得声明或依赖。
 */
export interface Agent {
  agentId: string
  agentKey: string
  name: string
  description: string
  avatarUrl: string
  agentVersion: number
  isDefault: boolean
  sortOrder: number
}
