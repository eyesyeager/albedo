/**
 * 会话接口（契约：docs/api-spec.md §4.5）。
 *
 * 🔴 全部需要登录；越权 / 跨租户统一 `10004`（不暴露资源存在性）。
 * 🔴 创建会话必须携带 `Idempotency-Key`，缺失返回 `10001`。
 */
import type { PageData } from '@/types/api'
import type { ChatMessage, Conversation } from '@/types/chat'
import request from '@/utils/request'

const BASE = '/api/v1/conversations'

export interface ConversationQuery {
  page?: number
  pageSize?: number
  keyword?: string
}

export interface MessageQuery {
  page?: number
  pageSize?: number
  /** 默认 false：只返回当前尝试（重新生成后的旧尝试不展示） */
  includeSuperseded?: boolean
}

export const conversationApi = {
  list: (query: ConversationQuery): Promise<PageData<Conversation>> =>
    request.get<PageData<Conversation>>(BASE, { params: { ...query } }),

  create: (agentId: string | null, idempotencyKey: string): Promise<Conversation> =>
    request.post<Conversation>(
      BASE,
      { agentId },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    ),

  detail: (conversationId: string): Promise<Conversation> =>
    request.get<Conversation>(`${BASE}/${encodeURIComponent(conversationId)}`),

  /** 重命名：必须回传乐观锁 version，冲突返回 `30020`（EX-023，绝不静默覆盖）。 */
  rename: (conversationId: string, title: string, expectedVersion: number): Promise<Conversation> =>
    request.patch<Conversation>(`${BASE}/${encodeURIComponent(conversationId)}`, {
      title,
      expectedVersion,
    }),

  remove: (conversationId: string): Promise<void> =>
    request.delete<void>(`${BASE}/${encodeURIComponent(conversationId)}`),

  messages: (conversationId: string, query: MessageQuery): Promise<PageData<ChatMessage>> =>
    request.get<PageData<ChatMessage>>(
      `${BASE}/${encodeURIComponent(conversationId)}/messages`,
      { params: { ...query } },
    ),
}
