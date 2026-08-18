/**
 * SSE 事件 → 消息视图的应用逻辑（契约：docs/api-spec.md §5）。
 *
 * 从 `stores/chat.ts` 抽出的理由：M3 的 `tool` / `error` 事件字段大幅扩展后，
 * 事件处理已具备独立的分支复杂度与测试价值（可脱离 Pinia 单测）。
 *
 * 🔴 纪律：
 *   1. `tool` 事件必须**按 `toolCallId` 原位更新**，不得每帧重建列表
 *   2. 未知事件名 / 未知字段 / 未知状态一律忽略或降级，绝不中断流（§5.4.1）
 *   3. 鉴权失效（20000~20005）已由 `streamRequest` 统一清退，🔴 此处不重复判定
 *   4. `10005` 只把服务端 `retryAfterSeconds` 交给限流状态，🔴 绝不自动重试
 */
import { ERROR_CODE } from '@/types/api'
import type { ChatMessageView } from '@/types/chat'
import type { ToolCallSummary } from '@/types/tool'
import { isTerminalToolStatus } from '@/types/tool'
import type { ChatStreamEvent, ToolStreamEvent } from '@/utils/streamRequest'
import { upsertToolCall } from '@/utils/toolCall'

export interface StreamHandlerContext {
  /** 本次生成对应的 assistant 消息 clientId */
  assistantClientId: string
  /** 读取该消息当前的工具调用列表（用于原位合并） */
  getToolCalls: () => readonly ToolCallSummary[]
  patch: (clientId: string, changes: Partial<ChatMessageView>) => void
  pushText: (text: string) => void
  /** 思考过程分片（🔴 独立通道，不得并入 pushText） */
  pushReasoning: (reasoning: string) => void
  flushText: () => void
  /** 首次拿到真实会话 ID（首页发送时由后端原子创建） */
  onConversationId: (conversationId: string) => void
  /** 回填用户消息 ID */
  onUserMessageId: (messageId: string) => void
  /** 第一个**用户可见帧**（首个 delta 或首个 tool，取先到者，§5.4.2） */
  onFirstVisibleFrame: () => void
  /** 每帧工具事件归一后的回调（登记确认卡截止时刻 / 终态埋点） */
  onToolCall: (call: ToolCallSummary, terminal: boolean) => void
  /** 命中 `10005`：交给限流状态倒计时 */
  onRateLimited: (retryAfterSeconds: number | null) => void
  onDone: (status: string) => void
}

/** 生成一个只对单次流有效的事件处理器。 */
export function createStreamHandler(ctx: StreamHandlerContext): (event: ChatStreamEvent) => void {
  let firstVisibleSeen = false

  function markFirstVisible(): void {
    if (firstVisibleSeen) {
      return
    }
    firstVisibleSeen = true
    ctx.onFirstVisibleFrame()
  }

  function applyTool(event: ToolStreamEvent): void {
    const call: ToolCallSummary = {
      toolCallId: event.toolCallId,
      toolType: event.toolType,
      toolKey: event.toolKey,
      status: event.status,
      riskLevel: event.riskLevel,
      round: event.round,
      summary: event.summary,
      argsSummary: event.argsSummary,
      resultSummary: event.resultSummary,
      truncated: event.truncated,
      errorCode: event.errorCode,
      retryAfterSeconds: event.retryAfterSeconds,
      // 🔴 逐字段拷贝的代价：漏一个字段就等于该字段在界面上不存在。
      //    本字段决定确认卡倒计时的真实总时长（api-spec §5.2 / ADR-017 ③ⓑ）。
      confirmExpiresInSeconds: event.confirmExpiresInSeconds ?? null,
    }
    if (call.toolCallId.length === 0) {
      // 无法定位的工具帧只能忽略（🔴 不得凭索引猜测目标，否则会串状态）
      console.error('[chat] 工具事件缺少 toolCallId，已忽略该帧')
      return
    }
    const merged = upsertToolCall(ctx.getToolCalls(), call)
    ctx.patch(ctx.assistantClientId, { toolCalls: merged })
    const applied = merged.find((item) => item.toolCallId === call.toolCallId) ?? call
    ctx.onToolCall(applied, isTerminalToolStatus(applied.status))
  }

  return function handle(event: ChatStreamEvent): void {
    switch (event.type) {
      case 'meta': {
        if (event.conversationId.length > 0) {
          ctx.onConversationId(event.conversationId)
        }
        ctx.patch(ctx.assistantClientId, {
          messageId: event.messageId,
          status: 'streaming',
          agentVersion: event.agentVersion,
        })
        if (event.userMessageId.length > 0) {
          ctx.onUserMessageId(event.userMessageId)
        }
        return
      }
      case 'delta':
        // 🔴 思考帧与正文帧分流（api-spec §5.2）：二者互斥，思考帧的 text 恒为空串。
        //    不能合并处理 —— 把思维链写进 content 会污染复制内容与会话标题。
        if (event.reasoning.length > 0) {
          markFirstVisible()
          ctx.pushReasoning(event.reasoning)
          return
        }
        if (event.text.length === 0) {
          // 空正文帧（旧后端的心跳式空帧）无需触发首字埋点与重渲染
          return
        }
        markFirstVisible()
        ctx.pushText(event.text)
        return
      case 'tool':
        markFirstVisible()
        applyTool(event)
        return
      case 'error':
        ctx.flushText()
        if (event.code === ERROR_CODE.RATE_LIMITED) {
          ctx.onRateLimited(event.retryAfterSeconds)
        }
        // 🔴 30000+ 一律按业务语义展示，绝不跳登录
        ctx.patch(ctx.assistantClientId, {
          errorMessage: event.message,
          errorCode: event.code,
        })
        return
      case 'done':
        ctx.flushText()
        ctx.patch(ctx.assistantClientId, {
          status: event.status,
          finishReason: event.finishReason,
          streaming: false,
          messageId: event.messageId.length > 0 ? event.messageId : undefined,
        })
        ctx.onDone(event.status)
        return
      default:
        // 🔴 未知事件名忽略即可（事件名集合固定，但保持解析层健壮）
        return
    }
  }
}
