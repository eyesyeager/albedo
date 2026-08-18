/**
 * SSE 事件处理器的装配（`stores/chat.ts` 的内部实现细节）。
 *
 * 抽出理由：`chat.ts` 已超 300 行，且"把 Store 的引用/埋点/限流接线到
 * `createStreamHandler`"是一段纯装配代码，与会话状态本身无关。
 *
 * 🔴 纪律：
 *   1. 鉴权失效（20000~20005）由 `streamRequest` 统一清退，🔴 此处不重复判定
 *   2. `10005` 只把服务端 `retryAfterSeconds` 交给限流状态，🔴 绝不自动重试
 *   3. 埋点只上报结构化元数据，🔴 绝不上报正文（见 `utils/chatAnalytics.ts`）
 */
import type { ChatMessageView } from '@/types/chat'
import type { ToolCallSummary } from '@/types/tool'
import {
  trackConversationCreate,
  trackFirstToken,
  trackMessageComplete,
  trackToolCallResult,
} from '@/utils/chatAnalytics'
import { createStreamHandler } from '@/utils/chatStreamHandler'
import type { ChatStreamEvent } from '@/utils/streamRequest'

export interface StreamHandlerDeps {
  assistantClientId: string
  /** 当前会话 ID（首页发送时流内才拿到，故用 getter） */
  conversationId: () => string | null
  /** 本次生成开始时刻（用于首字 / 完成耗时） */
  startedAt: () => number
  /** 本次发送选定的 Agent（仅用于会话创建埋点） */
  agentId: () => string | null
  /** 用户消息的 clientId（回填 messageId） */
  userClientId: () => string | null
  find: (clientId: string) => ChatMessageView | undefined
  patch: (clientId: string, changes: Partial<ChatMessageView>) => void
  pushText: (text: string) => void
  /** 思考过程分片（🔴 独立通道，不得并入 pushText） */
  pushReasoning: (reasoning: string) => void
  flushText: () => void
  /** 首次拿到真实会话 ID */
  onConversationId: (conversationId: string) => void
  /** 每帧工具事件归一后的回调（登记确认卡截止时刻） */
  observeToolCall: (call: ToolCallSummary) => void
  /** 命中 `10005`：交给限流状态倒计时 */
  onRateLimited: (retryAfterSeconds: number | null) => void
  /** 生成结束（含失败 / 停止）：同步会话摘要 */
  onFinished: () => void
}

export function buildChatStreamHandler(
  deps: StreamHandlerDeps,
): (event: ChatStreamEvent) => void {
  const { assistantClientId } = deps

  return createStreamHandler({
    assistantClientId,
    getToolCalls: () => deps.find(assistantClientId)?.toolCalls ?? [],
    patch: deps.patch,
    pushText: deps.pushText,
    pushReasoning: deps.pushReasoning,
    flushText: deps.flushText,
    onConversationId: (id) => {
      if (deps.conversationId() === null) {
        deps.onConversationId(id)
        trackConversationCreate(id, deps.agentId())
      }
    },
    onUserMessageId: (messageId) => {
      const clientId = deps.userClientId()
      if (clientId !== null) {
        deps.patch(clientId, { messageId, status: 'sent' })
      }
    },
    // 首字锚点 = 首个 delta 或首个 tool，取先到者（api-spec §5.4.2）
    onFirstVisibleFrame: () =>
      trackFirstToken(deps.conversationId(), Date.now() - deps.startedAt()),
    onToolCall: (call, terminal) => {
      deps.observeToolCall(call)
      if (terminal) {
        trackToolCallResult(call, deps.conversationId())
      }
    },
    onRateLimited: deps.onRateLimited,
    onDone: (status) => {
      trackMessageComplete(
        deps.conversationId(),
        status,
        Date.now() - deps.startedAt(),
        deps.find(assistantClientId)?.tokenUsage,
      )
      deps.onFinished()
    },
  })
}
