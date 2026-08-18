/**
 * 消息视图模型工厂：把接口对象与本地乐观态统一为同一形态，
 * 使消息列表组件不必区分"来自服务端"还是"刚刚发出"。
 *
 * 🔴 工具调用一律经 `normalizeToolCall` 归一（服务端字段可缺可空，M3 新增字段可为 null）。
 */
import type { ChatMessage, ChatMessageView, MessageRoundSegment } from '@/types/chat'
import { newClientId } from '@/utils/idempotency'
import { normalizeToolCall } from '@/utils/toolCall'

/**
 * 归一按轮次段落。
 *
 * 🔴 服务端可能返回 `null` / 省略字段 / 元素缺字段，一律容错为可安全渲染的形态；
 * 🔴 任何异常形状都退化为空数组 → 渲染层走旧版布局，**只降级排版、绝不丢内容**。
 */
function normalizeSegments(raw: ChatMessage['segments']): MessageRoundSegment[] {
  if (!Array.isArray(raw)) {
    return []
  }
  const segments: MessageRoundSegment[] = []
  for (const item of raw) {
    if (item === null || typeof item !== 'object') {
      continue
    }
    const round = Number(item.round)
    if (!Number.isFinite(round)) {
      continue
    }
    segments.push({
      round,
      reasoning: typeof item.reasoning === 'string' ? item.reasoning : '',
      text: typeof item.text === 'string' ? item.text : '',
    })
  }
  return segments
}

/** 服务端消息 → 视图模型。 */
export function toMessageView(message: ChatMessage): ChatMessageView {
  return {
    ...message,
    toolCalls: (message.toolCalls ?? []).map(normalizeToolCall),
    clientId: `s_${message.messageId}_${message.attemptNo}`,
    streaming: message.status === 'streaming' || message.status === 'queued',
    errorMessage: null,
    errorCode: null,
    // 🔴 归一 null / undefined → 空串：组件以 length > 0 判定是否渲染面板
    reasoning: message.reasoning ?? '',
    segments: normalizeSegments(message.segments),
  }
}

/** 本地乐观用户消息（messageId 在 meta 事件到达后回填）。 */
export function createUserMessage(content: string): ChatMessageView {
  return {
    messageId: '',
    role: 'user',
    content,
    status: 'pending',
    attemptNo: 1,
    isCurrent: true,
    toolCalls: [],
    createdAt: new Date().toISOString(),
    clientId: newClientId(),
    streaming: false,
    errorMessage: null,
    errorCode: null,
    reasoning: '',
    segments: [],
  }
}

/** 助手占位消息：先出现容器，再逐帧填充文本（避免布局跳动）。 */
export function createAssistantPlaceholder(): ChatMessageView {
  return {
    messageId: '',
    role: 'assistant',
    content: '',
    status: 'queued',
    attemptNo: 1,
    isCurrent: true,
    toolCalls: [],
    createdAt: new Date().toISOString(),
    clientId: newClientId(),
    streaming: true,
    errorMessage: null,
    errorCode: null,
    reasoning: '',
    segments: [],
  }
}
