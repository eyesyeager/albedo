/**
 * 对话 Store：历史加载 + 流式生成 + 停止 / 重试 / 重新生成 + M3 工具编排接入。
 *
 * 🔴 关键纪律：
 *   1. 流式必须走 `utils/streamRequest.ts`（fetch + ReadableStream）；EventSource 无法带 authorization 头
 *   2. `delta` 分片按帧合并写入（`utils/textBuffer.ts`），避免逐字 diff 造成重渲染风暴
 *   3. 用户主动停止：先 abort 本地流，再调用 `POST /messages/{id}/stop` 持久化 stopped（AC-CHAT-002）
 *   4. 发送失败重试**复用同一个幂等键**；已产生 assistant 消息的失败则走 regenerate（不重复用户消息）
 *   5. 鉴权失效（20000~20005）已由 streamRequest 统一清退，本文件不得重复判定
 *   6. M3：`tool` 帧按 `toolCallId` 原位更新；`10005` 交限流 Store 倒计时，🔴 不自动重试
 *   7. 日志分级：已被界面消费的业务错误（如 `10005`）只记 debug，网络 / 断流 / `50000+` 记 error
 *      （判定收口在 `utils/errorLevel.ts`，🔴 禁止在此散落裸码白名单）
 *   8. M3：confirm 接口回放的**服务端终态**（`toolConfirm.syncedStatus`）原位写回工具调用 ——
 *      SSE 已断开时这是唯一的收敛点（BUG-MCP-003）；🔴 判定条件不在此重复实现
 *   9. M3.1：`10005`（秒级自愈）与 `30070`（当日不可恢复）分别交给
 *      `rateLimitStore` / `quotaStore`，🔴 严禁互相复用状态；`done` 之后重取额度权威快照
 */
import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'

import { chatApi } from '@/api/chat'
import { resetAnnouncements } from '@/composables/useLiveAnnouncer'
import type {
  ChatMessageView,
  Conversation,
  MessageRoundSegment,
  PendingSend,
} from '@/types/chat'
import { trackMessageSend } from '@/utils/chatAnalytics'
import { loadConversationSnapshot } from '@/utils/chatHistory'
import { createAssistantPlaceholder, createUserMessage } from '@/utils/chatMessage'
import {
  applyToolCallStatus,
  cancelPendingToolCalls,
  findByClientId as findInList,
  patchMessage,
  retryPatch,
} from '@/utils/chatMessageList'
import { streamFailurePatch } from '@/utils/chatStreamFailure'
import { buildChatStreamHandler } from '@/utils/chatStreamWiring'
import { isUiHandledError } from '@/utils/errorLevel'
import { newIdempotencyKey } from '@/utils/idempotency'
import { isLoggedIn } from '@/utils/request'
import {
  buildRegenerateInvoker,
  buildSendInvoker,
  type StreamInvoker,
} from '@/utils/chatStreamInvoker'
import type { ChatStreamEvent } from '@/utils/streamRequest'
import { createTextBuffer } from '@/utils/textBuffer'

import { useConfigStore } from './config'
import { useConversationStore } from './conversation'
import { useQuotaStore } from './quota'
import { useRateLimitStore } from './rateLimit'
import { useToolConfirmStore } from './toolConfirm'

export const useChatStore = defineStore('chat', () => {
  const configStore = useConfigStore()
  const conversationStore = useConversationStore()
  const quotaStore = useQuotaStore()
  const rateLimitStore = useRateLimitStore()
  const toolConfirmStore = useToolConfirmStore()

  const conversationId = ref<string | null>(null)
  const conversation = ref<Conversation | null>(null)
  const messages = ref<ChatMessageView[]>([])
  const historyLoading = ref(false)
  const historyError = ref<string | null>(null)
  const generating = ref(false)
  const lastSend = ref<PendingSend | null>(null)

  let controller: AbortController | null = null
  let streamingClientId: string | null = null
  let streamStartedAt = 0
  /**
   * 本轮段落是否已封口（🔴 工具帧到达即置真）。
   *
   * 为什么需要它：工具调用之后模型会继续输出**新一轮**思考，
   * 若不封口就会把两轮思考拼成一段，工具节点又被挤到末尾 —— 正是要修的缺陷本身。
   */
  let roundSealed = false
  const buffer = createTextBuffer((merged) => appendStreamingText(merged))
  // 🔴 思考过程独立缓冲：与正文分开累积，避免思维链被拼进 content（api-spec §5.2 delta.reasoning）
  const reasoningBuffer = createTextBuffer((merged) => appendStreamingReasoning(merged))

  /** 刷出全部流式缓冲（🔴 新增缓冲必须在此登记，否则收尾时会丢分片）。 */
  function flushStreamBuffers(): void {
    buffer.flush()
    reasoningBuffer.flush()
  }

  /** 丢弃全部流式缓冲（切换会话 / 主动中断）。 */
  function cancelStreamBuffers(): void {
    buffer.cancel()
    reasoningBuffer.cancel()
  }

  const readOnly = computed(() => conversation.value?.status === 'readOnly')
  const messageMaxChars = computed(() => configStore.num('chat', 'messageMaxChars', 20000))
  const messageMinChars = computed(() => configStore.num('chat', 'messageMinChars', 1))

  /** 打开会话（null 表示回到首页的空白编辑态）。 */
  async function open(target: string | null): Promise<void> {
    abortStream()
    conversationId.value = target
    conversation.value = null
    messages.value = []
    historyError.value = null
    lastSend.value = null
    toolConfirmStore.reset()
    resetAnnouncements()
    if (target === null || !isLoggedIn()) {
      return
    }
    historyLoading.value = true
    const snapshot = await loadConversationSnapshot(
      target,
      configStore.num('business', 'pageSizeMax', 100),
    )
    historyLoading.value = false
    // 🔴 打开期间用户可能已切走：此时丢弃这次结果，避免串会话
    if (conversationId.value !== target) {
      return
    }
    conversation.value = snapshot.conversation
    messages.value = snapshot.messages
    historyError.value = snapshot.error
  }

  /** 发送消息（首页发送时由后端原子创建会话）。 */
  async function send(content: string, agentId: string | null): Promise<void> {
    // 🔴 QPM 等待中与日额度用尽都不发起请求，但两者是**独立**判定：
    //    前者秒级自愈（rateLimitStore），后者当日不可恢复（quotaStore），绝不互相替代。
    if (generating.value || rateLimitStore.waiting || quotaStore.exhausted) {
      return
    }
    const userMessage = createUserMessage(content)
    const assistantMessage = createAssistantPlaceholder()
    messages.value = [...messages.value, userMessage, assistantMessage]
    lastSend.value = {
      content,
      agentId,
      idempotencyKey: newIdempotencyKey(),
      userClientId: userMessage.clientId,
      assistantClientId: assistantMessage.clientId,
    }
    // 🔴 只上报字符数，绝不上报正文
    trackMessageSend(conversationId.value, [...content].length)
    await runStream(
      assistantMessage.clientId,
      buildSendInvoker(lastSend.value, conversationId.value),
    )
  }

  /** 流未建立即失败时的重发：复用同一幂等键，绝不重复创建用户消息（EX-013）。 */
  async function retrySend(): Promise<void> {
    const pending = lastSend.value
    if (pending === null || generating.value || rateLimitStore.waiting || quotaStore.exhausted) {
      return
    }
    resetForRetry(pending.assistantClientId)
    await runStream(
      pending.assistantClientId,
      buildSendInvoker(pending, conversationId.value),
    )
  }

  /** 重新生成 / 失败重试：新建 attemptNo+1 尝试，🔴 不重复保存用户消息（api-spec §4.6.3）。 */
  async function regenerate(clientId: string): Promise<void> {
    const target = findByClientId(clientId)
    if (target === undefined || generating.value || target.messageId.length === 0) {
      return
    }
    const invoker = buildRegenerateInvoker(target.messageId, newIdempotencyKey())
    resetForRetry(clientId)
    await runStream(clientId, invoker)
  }

  /**
   * 统一重试入口：
   *   - assistant 已有 messageId（流已建立）→ regenerate，保留旧尝试且不重复用户消息
   *   - 尚无 messageId（流未建立就失败）→ 复用原幂等键重发
   */
  async function retry(clientId: string): Promise<void> {
    const target = findByClientId(clientId)
    if (target === undefined) {
      return
    }
    if (target.messageId.length > 0) {
      await regenerate(clientId)
      return
    }
    await retrySend()
  }

  /**
   * 停止生成：先中断本地流并落地 stopped 语义，再请求服务端持久化。
   *
   * M3：确认等待中点击停止 → 冻结倒计时并禁用决策（🔴 停止 ≠ 拒绝）；
   * 服务端确认 stopped 后把未终态的工具调用原位收敛为 `cancelled`（api-spec §7.8.1）；
   * 停止失败则恢复服务端最新状态与剩余时间，🔴 不重置完整等待上限。
   */
  async function stop(): Promise<void> {
    const target = streamingClientId === null ? undefined : findByClientId(streamingClientId)
    const clientId = target?.clientId ?? ''
    const messageId = target?.messageId ?? ''
    toolConfirmStore.markStopping()
    abortStream()
    // 先本地落定，保证「停止后 ≤1s 不再追加」在网络异常时同样成立（AC-CHAT-002）
    if (clientId.length > 0) {
      patch(clientId, { status: 'stopped', streaming: false, finishReason: 'stopped' })
    }
    if (clientId.length === 0 || messageId.length === 0) {
      toolConfirmStore.clearStopping()
      return
    }
    try {
      const result = await chatApi.stop(messageId)
      patch(clientId, { status: result.status })
      // 🔴 只把非终态的工具调用转取消态，不伪造超时 / 成功
      const cancelled = cancelPendingToolCalls(findByClientId(clientId))
      if (cancelled !== null) {
        patch(clientId, { toolCalls: cancelled })
      }
    } catch (e: unknown) {
      console.error('[chat] 停止生成失败', e)
    } finally {
      toolConfirmStore.clearStopping()
    }
    void syncConversation()
  }

  function reset(): void {
    abortStream()
    conversationId.value = null
    conversation.value = null
    messages.value = []
    historyError.value = null
    lastSend.value = null
    toolConfirmStore.reset()
    resetAnnouncements()
  }

  // ===================== 内部实现 =====================

  async function runStream(assistantClientId: string, invoke: StreamInvoker): Promise<void> {
    generating.value = true
    streamingClientId = assistantClientId
    streamStartedAt = Date.now()
    // 🔴 每次新流都要重置轮次封口标记：重试 / 重新生成会复用同一个 store
    roundSealed = false
    controller = new AbortController()
    patch(assistantClientId, { errorCode: null, errorMessage: null })
    try {
      await invoke(buildHandler(assistantClientId), controller.signal)
    } catch (e: unknown) {
      flushStreamBuffers()
      // 流未建立即失败：10005 交限流倒计时（剩余秒数只来自服务端 data.retryAfterSeconds）
      rateLimitStore.startFromError(e)
      // 🔴 30070 自带同形快照（9 键）→ 用尽那一刻零延迟进入用尽态；
      //    🔴 它**不含** retryAfterSeconds，故上一行不会把它误表现为秒级倒计时（K5）
      quotaStore.applyRejection(e)
      patch(assistantClientId, streamFailurePatch(e))
      // 🔴 分级：已被限流倒计时 / 消息失败块消费的业务错误（如 10005）不报 error；
      //    网络失败、SSE 断流、50000+ 系统异常仍保留 error，绝不静音真问题。
      if (isUiHandledError(e)) {
        console.debug('[chat] 流式生成失败（已由界面呈现）', e)
      } else {
        console.error('[chat] 流式生成失败', e)
      }
    } finally {
      flushStreamBuffers()
      generating.value = false
      streamingClientId = null
      controller = null
    }
  }

  function buildHandler(assistantClientId: string): (event: ChatStreamEvent) => void {
    return buildChatStreamHandler({
      assistantClientId,
      conversationId: () => conversationId.value,
      startedAt: () => streamStartedAt,
      agentId: () => lastSend.value?.agentId ?? null,
      userClientId: () => lastSend.value?.userClientId ?? null,
      find: findByClientId,
      patch,
      pushText: (text) => buffer.push(text),
      pushReasoning: (reasoning) => reasoningBuffer.push(reasoning),
      flushText: () => flushStreamBuffers(),
      onConversationId: (id) => {
        conversationId.value = id
      },
      observeToolCall: (call) => {
        // 🔴 顺序不可颠倒：必须先刷出待提交的文本分片，再封口本轮。
        //    文本缓冲是 raf 批处理的，若先封口，已到达但尚未提交的分片
        //    会被错记进"工具之后的下一轮"，时间线顺序即刻错位。
        flushStreamBuffers()
        roundSealed = true
        toolConfirmStore.observe(call)
      },
      onRateLimited: (retryAfterSeconds) => rateLimitStore.start(retryAfterSeconds),
      onFinished: () => {
        void syncConversation()
        // 🔴 额度快照**不进** `done` 帧（ADR-020 备选 E）：生成结束后重取权威快照，
        //    并顺带通知其他标签页校准 —— 与"恢复前台 / 到达 resetsAt"复用同一条路径。
        quotaStore.refreshAfterSettlement()
      },
    })
  }

  async function syncConversation(): Promise<void> {
    const id = conversationId.value
    if (id === null) {
      return
    }
    const synced = await conversationStore.syncOne(id)
    if (synced !== null) {
      conversation.value = synced
    }
  }

  function appendStreamingText(text: string): void {
    if (streamingClientId === null) {
      return
    }
    const target = findByClientId(streamingClientId)
    if (target === undefined) {
      return
    }
    target.content += text
    currentSegment(target).text += text
  }

  /** 思考过程累积（🔴 写 reasoning 而非 content：后者参与落库、复制与标题生成）。 */
  function appendStreamingReasoning(reasoning: string): void {
    if (streamingClientId === null) {
      return
    }
    const target = findByClientId(streamingClientId)
    if (target === undefined) {
      return
    }
    target.reasoning += reasoning
    currentSegment(target).reasoning += reasoning
  }

  /**
   * 取当前轮段落，必要时开新一轮。
   *
   * 🔴 轮号必须与后端 `tool_calls.round` 同源（都从 1 起、每次工具派发后 +1），
   * 否则刷新页面后（改用服务端 `segments`）时间线顺序会与刚才看到的不一致。
   */
  function currentSegment(target: ChatMessageView): MessageRoundSegment {
    const last = target.segments[target.segments.length - 1]
    if (last !== undefined && !roundSealed) {
      return last
    }
    // 工具帧到达即封口本轮；其后的第一片思考/正文属于下一轮
    const created: MessageRoundSegment = {
      round: (last?.round ?? 0) + 1,
      reasoning: '',
      text: '',
    }
    target.segments.push(created)
    roundSealed = false
    return created
  }

  function resetForRetry(clientId: string): void {
    patch(clientId, retryPatch())
  }

  function abortStream(): void {
    cancelStreamBuffers()
    controller?.abort()
    controller = null
    streamingClientId = null
    generating.value = false
  }

  function findByClientId(clientId: string): ChatMessageView | undefined {
    return findInList(messages.value, clientId)
  }

  function patch(clientId: string, changes: Partial<ChatMessageView>): void {
    patchMessage(messages.value, clientId, changes)
  }

  /**
   * confirm 响应回放的服务端终态 → 原位收敛工具卡片（BUG-MCP-003）。
   *
   * 🔴 为什么必须有这条兜底：SSE 可能已因整体超时/取消提前关闭，
   * 该 `toolCallId` 不会再有 `tool` 帧，卡片会永久停在"正在提交你的决定…"。
   * 🔴 入表条件已在 `toolConfirm.syncReplayedTerminal` 收口（仅 `replayed=true` 的终态），
   * 本处只负责写回消息列表，🔴 不做第二套判定、不改写已到达的终态（由 `applyToolCallStatus` 保证）。
   */
  watch(
    () => toolConfirmStore.syncedStatus,
    (synced) => {
      Object.entries(synced).forEach(([toolCallId, status]) => {
        const applied = applyToolCallStatus(messages.value, toolCallId, status)
        if (applied !== null) {
          patch(applied.clientId, { toolCalls: applied.toolCalls })
        }
      })
    },
  )

  return {
    conversationId,
    conversation,
    messages,
    historyLoading,
    historyError,
    generating,
    readOnly,
    messageMaxChars,
    messageMinChars,
    open,
    send,
    retrySend,
    retry,
    regenerate,
    stop,
    reset,
  }
})
