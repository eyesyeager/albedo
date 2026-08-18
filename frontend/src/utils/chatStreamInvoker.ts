/**
 * 流式调用器构造（`stores/chat.ts` 的内部实现细节）。
 *
 * 抽出理由：`chat.ts` 已超 300 行；"发送 / 重新生成分别对应哪个流接口、
 * 幂等键怎么复用"是独立且易写错的规则，值得单独承载。
 *
 * 🔴 纪律：
 *   1. 发送失败重试**复用同一个幂等键**（EX-013：否则会重复创建用户消息）
 *   2. 重新生成用新幂等键，走 regenerate 接口，🔴 不重复保存用户消息（api-spec §4.6.3）
 */
import type { PendingSend } from '@/types/chat'
import {
  streamChat,
  streamRegenerate,
  type ChatStreamEvent,
  type StreamChatOptions,
} from '@/utils/streamRequest'

/** `conversationId` 的特殊值：原子创建会话 + 保存首条消息（api-spec §4.6.1）。 */
export const NEW_CONVERSATION = 'new'

export type StreamInvoker = (
  onEvent: (event: ChatStreamEvent) => void,
  signal: AbortSignal,
) => Promise<void>

/**
 * 发送 / 重发调用器。
 *
 * @param conversationId 已有会话 ID；null 表示首页发送（由后端原子创建）
 */
export function buildSendInvoker(
  pending: PendingSend,
  conversationId: string | null,
): StreamInvoker {
  const options: Omit<StreamChatOptions, 'onEvent' | 'signal'> = {
    conversationId: conversationId ?? NEW_CONVERSATION,
    body:
      pending.agentId === null
        ? { content: pending.content }
        : { content: pending.content, agentId: pending.agentId },
    // 🔴 复用原幂等键：重发绝不产生第二条用户消息
    idempotencyKey: pending.idempotencyKey,
  }
  return (onEvent, signal) => streamChat({ ...options, onEvent, signal })
}

/** 重新生成调用器（新 attemptNo，🔴 不重复保存用户消息）。 */
export function buildRegenerateInvoker(messageId: string, idempotencyKey: string): StreamInvoker {
  return (onEvent, signal) => streamRegenerate(messageId, { idempotencyKey, signal, onEvent })
}
