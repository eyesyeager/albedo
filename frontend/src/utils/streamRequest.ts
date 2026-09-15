/**
 * 流式对话 SSE 解析（fetch + ReadableStream 手写解析）。
 *
 * 🔴 V2（AG-UI 协议）：请求体改为标准 {@code RunAgentInput}，响应为 AG-UI 事件流
 * （{@code data: {"type":"RUN_STARTED",...}}，事件类型内嵌在 {@code data} 的 {@code type} 字段，
 * 不再使用 SSE 的 {@code event:} 行）。
 *
 * 为什么不用 EventSource：EventSource 无法设置自定义请求头 → 无法携带 `authorization`
 * （本项目 Token 只走 Header，禁止落 URL）。详见 docs/architecture.md §9.4。
 *
 * 🔴 鉴权行为必须与 utils/request.ts **完全一致**：
 *   1. 注入 Header authorization
 *   2. 响应头出现新 token 立即回写 localStorage（auth-type=1 续期，ADR-007）
 *   3. body.code 或 error 事件 code ∈ [20000..20005] → 清 token + 整页跳 SSO
 *   4. 30000+ 业务码 → 交由调用方展示，绝不跳登录
 */
import {
  ApiError,
  NetworkError,
  applyRenewedToken,
  buildAuthHeaders,
  redirectToSso,
  resolveUrl,
} from '@/utils/request'
import { AUTH_ERROR_CODES, type ApiResult } from '@/types/api'
import type { ToolCallStatusValue, ToolType } from '@/types/tool'
import { normalizeToolCall } from '@/utils/toolCall'

/**
 * 工具调用状态（契约保持兼容）。
 * 🔴 定义已迁移到 `types/tool.ts`，此处保留导出以维持既有引用。
 */
export type { ToolCallStatus, ToolCallStatusValue } from '@/types/tool'

/** 生成结束原因。 */
export type FinishReason = 'stop' | 'length' | 'stopped' | 'failed' | 'tool_denied' | 'timeout'

/** 助手消息最终持久化状态。 */
export type AssistantFinalStatus = 'completed' | 'stopped' | 'failed'

/**
 * `tool` 事件（内部契约，由 AG-UI `Custom(tool_progress)` 翻译而来）。
 *
 * 🔴 字段与后端 `toolProgressPayload` 输出一一对应；
 * 🔴 `status` 容忍未知取值，绝不因此中断流。
 */
export interface ToolStreamEvent {
  type: 'tool'
  toolCallId: string
  toolType: ToolType
  toolKey: string
  status: ToolCallStatusValue
  round: number
  summary: string
  argsSummary: string
  resultSummary: string
  truncated: boolean
  errorCode: number | null
  retryAfterSeconds: number | null
}

/** `error` 事件（内部契约，由 AG-UI `RUN_ERROR` 翻译而来）。 */
export interface ErrorStreamEvent {
  type: 'error'
  code: number
  message: string
  retryAfterSeconds: number | null
}

/**
 * 内部统一流事件（判别联合，禁止 any）。
 *
 * 🔴 这是「AG-UI 协议边界翻译」之后的稳定内部契约，下游 store / handler / 渲染零改动。
 */
export type ChatStreamEvent =
  | {
      type: 'meta'
      conversationId: string
      messageId: string
      agentVersion: number
      userMessageId: string
    }
  | { type: 'delta'; text: string; reasoning: string }
  | ToolStreamEvent
  | ErrorStreamEvent
  | {
      type: 'done'
      finishReason: FinishReason
      messageId: string
      status: AssistantFinalStatus
      title: string | null
    }

/** AG-UI 请求体：消息（本项目仅取最后一条 user 消息）。 */
export interface AgUiMessage {
  role: string
  content: string
}

/** AG-UI 请求体：中断恢复条目（工具确认）。 */
export interface AgUiResumeEntry {
  interruptId: string
  status: 'resolved' | 'cancelled'
  payload?: unknown
}

/** AG-UI 请求体 {@code RunAgentInput}。 */
export interface RunAgentInput {
  threadId: string
  runId: string
  parentRunId?: string
  state?: unknown
  messages: AgUiMessage[]
  tools?: unknown[]
  context?: unknown[]
  forwardedProps?: Record<string, unknown>
  resume?: AgUiResumeEntry[]
}

/** 发送消息选项（AG-UI 版）。 */
export interface StreamChatOptions {
  /** 已有会话 ID，或特殊值 'new' 表示原子创建会话 + 保存首条消息 */
  conversationId: string
  body: { content: string; agentId?: string }
  /** 幂等键（对应 AG-UI 的 runId）：重试必须复用同一个值 */
  idempotencyKey: string
  /** 用户主动中断 */
  signal?: AbortSignal
  /** 事件回调（按到达顺序） */
  onEvent: (event: ChatStreamEvent) => void
}

const AGUI_RUN_PATH = '/api/v1/agui/run'

/**
 * 发送消息并消费 AG-UI 事件流。
 *
 * @throws ApiError 业务错误（含流建立前的 JSON 错误响应）
 * @throws NetworkError 网络 / 协议层错误
 */
export async function streamChat(options: StreamChatOptions): Promise<void> {
  const input = buildRunAgentInput(options.conversationId, options.idempotencyKey, options.body)
  return consumeStream(AGUI_RUN_PATH, input, options)
}

/**
 * 重新生成并消费 AG-UI 事件流（不重复保存用户消息）。
 *
 * 🔴 regenerate 语义复用 {@code /run} 端点：以 {@code forwardedProps.regenerateMessageId}
 * 标识，后端据此走 {@code prepareRegenerate} 路径（从 messageId 反查会话）。
 */
export async function streamRegenerate(
  messageId: string,
  options: Omit<StreamChatOptions, 'conversationId' | 'body'>,
): Promise<void> {
  const input: RunAgentInput = {
    threadId: '',
    runId: options.idempotencyKey,
    messages: [],
    tools: [],
    context: [],
    forwardedProps: { regenerateMessageId: messageId },
  }
  return consumeStream(AGUI_RUN_PATH, input, options)
}

function buildRunAgentInput(
  conversationId: string,
  idempotencyKey: string,
  body: { content: string; agentId?: string },
): RunAgentInput {
  const forwardedProps: Record<string, unknown> = {}
  if (body.agentId !== undefined) {
    forwardedProps.agentId = body.agentId
  }
  return {
    threadId: conversationId,
    runId: idempotencyKey,
    messages: [{ role: 'user', content: body.content }],
    tools: [],
    context: [],
    forwardedProps,
  }
}

async function consumeStream(
  path: string,
  input: RunAgentInput,
  options: Omit<StreamChatOptions, 'conversationId' | 'body'>,
): Promise<void> {
  let response: Response
  try {
    response = await fetch(resolveUrl(path), {
      method: 'POST',
      headers: buildAuthHeaders({
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        'Idempotency-Key': options.idempotencyKey,
      }),
      body: JSON.stringify(input),
      signal: options.signal,
      credentials: 'omit',
    })
  } catch (e: unknown) {
    if (isAbort(e)) {
      return
    }
    throw new NetworkError(e instanceof Error ? e.message : 'network error')
  }

  applyRenewedToken(response.headers)

  if (!response.ok) {
    throw new NetworkError(`HTTP ${response.status}`)
  }

  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('text/event-stream')) {
    const payload = (await response.json()) as ApiResult<unknown>
    if (AUTH_ERROR_CODES.includes(payload.code)) {
      redirectToSso()
      throw new ApiError(payload.code, payload.message, payload.data)
    }
    throw new ApiError(payload.code, payload.message, payload.data)
  }

  if (response.body === null) {
    throw new NetworkError('response body is not readable')
  }

  // 🔴 AG-UI 事件翻译器：把标准 AG-UI 事件归一为内部 ChatStreamEvent
  const translator = createAgUiTranslator(options.onEvent)

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })

      let separator = findSeparator(buffer)
      while (separator.index >= 0) {
        const rawFrame = buffer.slice(0, separator.index)
        buffer = buffer.slice(separator.index + separator.length)
        handleFrame(rawFrame, translator)
        separator = findSeparator(buffer)
      }
    }
    if (buffer.trim().length > 0) {
      handleFrame(buffer, translator)
    }
  } catch (e: unknown) {
    if (isAbort(e)) {
      return
    }
    throw new NetworkError(e instanceof Error ? e.message : 'stream error')
  } finally {
    reader.releaseLock()
  }
}

/** SSE 帧分隔符：兼容 \n\n 与 \r\n\r\n。 */
function findSeparator(buffer: string): { index: number; length: number } {
  const lf = buffer.indexOf('\n\n')
  const crlf = buffer.indexOf('\r\n\r\n')
  if (lf < 0 && crlf < 0) {
    return { index: -1, length: 0 }
  }
  if (crlf >= 0 && (lf < 0 || crlf < lf)) {
    return { index: crlf, length: 4 }
  }
  return { index: lf, length: 2 }
}

/**
 * 解析单个 SSE 帧。
 *
 * 🔴 AG-UI 帧形如 {@code data: {"type":"RUN_STARTED",...}}，事件类型在 data 的 {@code type}
 * 字段；也兼容带 {@code event:} 行的旧格式（忽略 event 行，统一读 data）。
 */
function handleFrame(rawFrame: string, translator: (data: Record<string, unknown>) => void): void {
  const dataLines: string[] = []
  for (const line of rawFrame.split(/\r?\n/)) {
    if (line.length === 0 || line.startsWith(':')) {
      continue // 心跳注释帧 `: ping`
    }
    if (line.startsWith('event:')) {
      continue // AG-UI 不使用 event 行，忽略
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart())
    }
  }
  if (dataLines.length === 0) {
    return
  }
  let parsed: Record<string, unknown>
  try {
    parsed = JSON.parse(dataLines.join('\n')) as Record<string, unknown>
  } catch {
    return // 非法帧忽略，等待后续 RUN_FINISHED/RUN_ERROR 给出确定结果
  }
  translator(parsed)
}

/**
 * AG-UI 事件 → 内部 {@link ChatStreamEvent} 的翻译器（有状态：需缓存 completion 信息）。
 */
function createAgUiTranslator(onEvent: (event: ChatStreamEvent) => void): (data: Record<string, unknown>) => void {
  let conversationId = ''
  let messageId = ''
  let agentVersion = 0
  let userMessageId = ''
  let metaEmitted = false

  let completion: {
    finishReason: FinishReason
    messageId: string
    status: AssistantFinalStatus
    title: string | null
    errorCode?: number
    errorMessage?: string
  } | null = null

  return function translate(data: Record<string, unknown>): void {
    const type = str(data.type)
    switch (type) {
      case 'RUN_STARTED': {
        const input = asRecord(data.input)
        conversationId = str(input?.conversationId ?? data.threadId)
        userMessageId = str(input?.userMessageId)
        agentVersion = num(input?.agentVersion ?? 0)
        messageId = str(data.runId)
        if (!metaEmitted) {
          metaEmitted = true
          onEvent({ type: 'meta', conversationId, messageId, agentVersion, userMessageId })
        }
        return
      }
      case 'TEXT_MESSAGE_START':
        return // 文本开始无需动作（内容由 CONTENT 承载）
      case 'TEXT_MESSAGE_CONTENT':
        onEvent({ type: 'delta', text: str(data.delta), reasoning: '' })
        return
      case 'TEXT_MESSAGE_END':
        return
      case 'REASONING_MESSAGE_CONTENT':
        onEvent({ type: 'delta', text: '', reasoning: str(data.delta) })
        return
      case 'REASONING_START':
      case 'REASONING_MESSAGE_START':
      case 'REASONING_MESSAGE_END':
      case 'REASONING_END':
        return
      case 'TOOL_CALL_START':
      case 'TOOL_CALL_ARGS':
      case 'TOOL_CALL_END':
      case 'TOOL_CALL_RESULT':
        return // 工具状态由 Custom(tool_progress) 承载，标准工具事件仅作骨架（忽略）
      case 'CUSTOM': {
        const name = str(data.name)
        if (name === 'tool_progress') {
          onEvent(toToolEvent(asRecord(data.value)))
        } else if (name === 'completion') {
          const value = asRecord(data.value)
          completion = {
            finishReason: str(value?.finishReason) as FinishReason,
            messageId: str(value?.messageId),
            status: str(value?.status) as AssistantFinalStatus,
            title: typeof value?.title === 'string' ? value.title : null,
            errorCode: typeof value?.errorCode === 'number' ? value.errorCode : undefined,
            errorMessage: typeof value?.errorMessage === 'string' ? value.errorMessage : undefined,
          }
        }
        return
      }
      case 'RUN_ERROR': {
        // 🔴 code 优先取 RUN_ERROR 事件的 code 字段（AG-UI 标准，字符串），fallback 到 completion.errorCode
        const rawCode = data.code === undefined || data.code === null
          ? completion?.errorCode ?? 50000
          : Number(data.code)
        const code = Number.isFinite(rawCode) ? rawCode : 50000
        const message = completion?.errorMessage ?? str(data.message)
        if (AUTH_ERROR_CODES.includes(code)) {
          redirectToSso()
          return
        }
        onEvent({ type: 'error', code, message, retryAfterSeconds: null })
        // 🔴 RUN_ERROR 之后仍需发 done（等价旧契约"error 之后仍须发 done"）
        emitDone(completion, messageId)
        return
      }
      case 'RUN_FINISHED': {
        emitDone(completion, messageId)
        return
      }
      default:
        return // 未知事件忽略（健壮性）
    }
  }

  function emitDone(
    completion: {
      finishReason: FinishReason
      messageId: string
      status: AssistantFinalStatus
      title: string | null
    } | null,
    fallbackMessageId: string,
  ): void {
    onEvent({
      type: 'done',
      finishReason: completion?.finishReason ?? 'stop',
      messageId: completion?.messageId ?? fallbackMessageId,
      status: completion?.status ?? 'completed',
      title: completion?.title ?? null,
    })
  }
}

function toToolEvent(value: Record<string, unknown> | undefined): ToolStreamEvent {
  const call = normalizeToolCall({
    toolCallId: optionalStr(value?.toolCallId),
    toolType: optionalStr(value?.toolType),
    toolKey: optionalStr(value?.toolKey),
    status: optionalStr(value?.status),
    round: optionalNum(value?.round),
    summary: optionalStr(value?.summary),
    argsSummary: optionalStr(value?.argsSummary),
    resultSummary: optionalStr(value?.resultSummary),
    truncated: value?.truncated === true,
    errorCode: optionalNum(value?.errorCode) ?? null,
    retryAfterSeconds: optionalNum(value?.retryAfterSeconds) ?? null,
  })
  return { type: 'tool', ...call }
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' ? (value as Record<string, unknown>) : undefined
}

function str(value: unknown): string {
  return typeof value === 'string' ? value : value === undefined || value === null ? '' : String(value)
}

function num(value: unknown): number {
  return typeof value === 'number' ? value : Number(value ?? 0)
}

function optionalStr(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined
}

function optionalNum(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function isAbort(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError'
}
