/**
 * 对话链路埋点（契约：docs/api-spec.md §7.10.1、PRD §15.2）。
 *
 * 🔴 只上报**结构化元数据**：会话/助手 ID、状态、耗时、字符数、token 用量。
 * 🔴 绝不上报消息正文、工具入参/结果正文、token、凭据、手机号、邮箱
 *    （`utils/analytics.ts` 还有一层键名 + 值级拦截作为兜底）。
 */
import { ANALYTICS_EVENT, track } from '@/utils/analytics'
import type { ToolCallSummary } from '@/types/tool'
import type { TokenUsage } from '@/types/chat'

export function trackMessageSend(conversationId: string | null, charCount: number): void {
  track(ANALYTICS_EVENT.messageSend, {
    ...(conversationId === null ? {} : { conversationId }),
    charCount,
  })
}

export function trackConversationCreate(conversationId: string, agentId: string | null): void {
  track(ANALYTICS_EVENT.conversationCreate, {
    conversationId,
    ...(agentId === null ? {} : { agentId }),
    source: 'newChat',
  })
}

/**
 * 首字埋点。
 * 🔴 锚点与 api-spec §5.4.2 一致：**首个 delta 或首个 tool 帧，取先到者**
 * （首轮即工具调用的生成不存在前置 delta）。
 */
export function trackFirstToken(conversationId: string | null, latencyMs: number): void {
  track(ANALYTICS_EVENT.messageFirstToken, {
    ...(conversationId === null ? {} : { conversationId }),
    latencyMs,
  })
}

export function trackMessageComplete(
  conversationId: string | null,
  status: string,
  durationMs: number,
  tokenUsage?: TokenUsage,
): void {
  track(ANALYTICS_EVENT.messageComplete, {
    ...(conversationId === null ? {} : { conversationId }),
    status,
    durationMs,
    ...(tokenUsage === undefined ? {} : { tokenUsage }),
  })
}

/** 工具调用终态埋点（🔴 只上报 toolKey / 状态 / 错误码，不含摘要文本）。 */
export function trackToolCallResult(call: ToolCallSummary, conversationId: string | null): void {
  track(ANALYTICS_EVENT.toolCallResult, {
    ...(conversationId === null ? {} : { conversationId }),
    toolType: call.toolType,
    toolKey: call.toolKey,
    status: call.status,
    result: toResult(call.status),
    ...(call.errorCode === null ? {} : { errorCode: call.errorCode }),
  })
}

function toResult(status: string): 'success' | 'failed' | 'denied' {
  if (status === 'succeeded') {
    return 'success'
  }
  return status === 'denied' || status === 'cancelled' ? 'denied' : 'failed'
}
