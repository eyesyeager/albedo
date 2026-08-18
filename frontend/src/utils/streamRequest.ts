/**
 * 流式对话 SSE 解析（fetch + ReadableStream 手写解析）。
 *
 * 为什么不用 EventSource：EventSource 无法设置自定义请求头 → 无法携带 `authorization`
 * （本项目 Token 只走 Header，禁止落 URL）。详见 docs/architecture.md §9.4。
 *
 * 🔴 鉴权行为必须与 utils/request.ts **完全一致**：
 *   1. 注入 Header authorization
 *   2. 响应头出现新 token 立即回写 localStorage（auth-type=1 续期，ADR-007）
 *   3. body.code 或 error 事件 code ∈ [20000..20005] → 清 token + 整页跳 SSO
 *   4. 30000+ 业务码 → 交由调用方展示，绝不跳登录
 *
 * 事件契约见 docs/api-spec.md §5。
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
import type { ToolCallStatusValue, ToolRiskLevel, ToolType } from '@/types/tool'
import { normalizeToolCall } from '@/utils/toolCall'

/**
 * 工具调用状态（api-spec §5.2）。
 * 🔴 定义已迁移到 `types/tool.ts`（M3 起为工具域公共类型），此处保留导出以维持既有引用。
 */
export type { ToolCallStatus, ToolCallStatusValue } from '@/types/tool'

/** 生成结束原因。 */
export type FinishReason = 'stop' | 'length' | 'stopped' | 'failed' | 'tool_denied' | 'timeout'

/** 助手消息最终持久化状态。 */
export type AssistantFinalStatus = 'completed' | 'stopped' | 'failed'

/**
 * `tool` 事件（api-spec §5.2，字段与契约字面一致）。
 *
 * 🔴 M3 新增字段一律可空 / 可缺失，解析层已归一为确定形态；
 * 🔴 `status` / `riskLevel` 容忍未知取值，绝不因此中断流（§5.4.1）。
 */
export interface ToolStreamEvent {
  type: 'tool'
  toolCallId: string
  toolType: ToolType
  toolKey: string
  riskLevel: ToolRiskLevel | ''
  status: ToolCallStatusValue
  round: number
  /** 🔴 兼容字段，永久保留：当前阶段可展示摘要 */
  summary: string
  argsSummary: string
  resultSummary: string
  truncated: boolean
  errorCode: number | null
  retryAfterSeconds: number | null
  /**
   * 本次确认的实际剩余等待秒数（V1.2.2）：🔴 仅 `awaiting_confirmation` 帧非空。
   * 🔴 可选：旧后端不下发该字段，缺失与 `null` 同义（前端回退全局 `tool.confirmWaitSeconds`）。
   */
  confirmExpiresInSeconds?: number | null
}

/** `error` 事件（api-spec §5.2；`retryAfterSeconds` 在 `code=10005` 时必填）。 */
export interface ErrorStreamEvent {
  type: 'error'
  code: number
  message: string
  retryAfterSeconds: number | null
}

/** SSE 事件（判别联合，禁止 any）。 */
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


/** 发送消息请求体（api-spec §4.6.1）。 */
export interface SendMessagePayload {
  content: string
  /** 仅当 conversationId 为 'new' 时有意义；已有会话忽略（会话绑定的 Agent 版本不变） */
  agentId?: string
}

export interface StreamChatOptions {
  /** 已有会话 ID，或特殊值 'new' 表示原子创建会话 + 保存首条消息 */
  conversationId: string
  body: SendMessagePayload
  /** 幂等键（UUID）：重试必须复用同一个值，避免重复创建用户消息 */
  idempotencyKey: string
  /** 用户主动中断（中断后仍需调用 POST /api/v1/messages/{id}/stop 持久化 stopped 状态） */
  signal?: AbortSignal
  /** 事件回调（按到达顺序） */
  onEvent: (event: ChatStreamEvent) => void
}

/**
 * 发送消息并消费 SSE 流。
 *
 * @throws ApiError 业务错误（含流建立前的 JSON 错误响应）
 * @throws NetworkError 网络 / 协议层错误
 */
export async function streamChat(options: StreamChatOptions): Promise<void> {
  const path = `/api/v1/conversations/${encodeURIComponent(options.conversationId)}/messages`
  return consumeStream(path, options.body, options)
}

/**
 * 重新生成并消费 SSE 流（不重复保存用户消息）。
 */
export async function streamRegenerate(
  messageId: string,
  options: Omit<StreamChatOptions, 'conversationId' | 'body'>,
): Promise<void> {
  const path = `/api/v1/messages/${encodeURIComponent(messageId)}/regenerate`
  return consumeStream(path, {}, options)
}

async function consumeStream(
  path: string,
  body: unknown,
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
      body: JSON.stringify(body),
      signal: options.signal,
      credentials: 'omit',
    })
  } catch (e: unknown) {
    if (isAbort(e)) {
      return
    }
    throw new NetworkError(e instanceof Error ? e.message : 'network error')
  }

  // ① 续期 token 回写（与 request.ts 行为一致）
  applyRenewedToken(response.headers)

  if (!response.ok) {
    throw new NetworkError(`HTTP ${response.status}`)
  }

  // ② 流未建立就失败时，后端返回标准 JSON Result（api-spec §4.6.1）
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('text/event-stream')) {
    const payload = (await response.json()) as ApiResult<unknown>
    if (AUTH_ERROR_CODES.includes(payload.code)) {
      redirectToSso()
      throw new ApiError(payload.code, payload.message, payload.data)
    }
    // 🔴 data 必须透传：`10005` 的 retryAfterSeconds 只在 data 中（api-spec §7.12）
    throw new ApiError(payload.code, payload.message, payload.data)
  }

  if (response.body === null) {
    // 技术性错误信息（仅进日志）；用户可见文案由调用方取 locales，🔴 不直接展示本串
    throw new NetworkError('response body is not readable')
  }

  // ③ 解析 SSE 帧
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
        handleFrame(rawFrame, options.onEvent)
        separator = findSeparator(buffer)
      }
    }
    // 收尾：处理最后一帧（服务端未以空行结尾的兜底）
    if (buffer.trim().length > 0) {
      handleFrame(buffer, options.onEvent)
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

function handleFrame(rawFrame: string, onEvent: (event: ChatStreamEvent) => void): void {
  let eventName = ''
  const dataLines: string[] = []

  for (const line of rawFrame.split(/\r?\n/)) {
    if (line.length === 0 || line.startsWith(':')) {
      continue // 心跳注释帧 `: ping`
    }
    if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart())
    }
  }

  if (eventName.length === 0 || dataLines.length === 0) {
    return
  }

  let parsed: Record<string, unknown>
  try {
    parsed = JSON.parse(dataLines.join('\n')) as Record<string, unknown>
  } catch {
    return // 非法帧忽略，等待后续 error/done 事件给出确定结果
  }

  const event = toStreamEvent(eventName, parsed)
  if (event === null) {
    return
  }

  // 鉴权失效在流内出现时，同样清 token + 整页跳 SSO
  if (event.type === 'error' && AUTH_ERROR_CODES.includes(event.code)) {
    redirectToSso()
    return
  }
  onEvent(event)
}

function toStreamEvent(name: string, data: Record<string, unknown>): ChatStreamEvent | null {
  switch (name) {
    case 'meta':
      return {
        type: 'meta',
        conversationId: str(data.conversationId),
        messageId: str(data.messageId),
        agentVersion: num(data.agentVersion),
        userMessageId: str(data.userMessageId),
      }
    case 'delta':
      // 🔴 `reasoning` 与 `text` 互斥承载（api-spec §5.2）：思考帧的 text 恒为空串。
      //    缺失 / null 一律归一为空串，旧后端（不下发该字段）行为完全不变。
      return { type: 'delta', text: str(data.text), reasoning: optionalStr(data.reasoning) ?? '' }
    case 'tool': {
      // 🔴 归一后再向上抛：未知字段忽略、缺失字段补默认、未知枚举原样保留（§5.4.1）
      const call = normalizeToolCall({
        toolCallId: optionalStr(data.toolCallId),
        toolType: optionalStr(data.toolType),
        toolKey: optionalStr(data.toolKey),
        riskLevel: optionalStr(data.riskLevel),
        status: optionalStr(data.status),
        round: optionalNum(data.round),
        summary: optionalStr(data.summary),
        argsSummary: optionalStr(data.argsSummary),
        resultSummary: optionalStr(data.resultSummary),
        truncated: data.truncated === true,
        errorCode: optionalNum(data.errorCode) ?? null,
        retryAfterSeconds: optionalNum(data.retryAfterSeconds) ?? null,
        // 🔴 必须透传：确认倒计时的真实剩余秒数只有本字段能给（ADR-017 ③ⓑ），
        //    丢弃它会让前端按全局 120s 显示一个必然骗人的倒计时
        confirmExpiresInSeconds: optionalNum(data.confirmExpiresInSeconds) ?? null,
      })
      return { type: 'tool', ...call }
    }
    case 'error':
      return {
        type: 'error',
        code: num(data.code),
        message: str(data.message),
        retryAfterSeconds: optionalNum(data.retryAfterSeconds) ?? null,
      }
    case 'done':
      return {
        type: 'done',
        finishReason: str(data.finishReason) as FinishReason,
        messageId: str(data.messageId),
        status: str(data.status) as AssistantFinalStatus,
        title: typeof data.title === 'string' ? data.title : null,
      }
    default:
      return null
  }
}

function str(value: unknown): string {
  return typeof value === 'string' ? value : value === undefined || value === null ? '' : String(value)
}

function num(value: unknown): number {
  return typeof value === 'number' ? value : Number(value ?? 0)
}

/** 字符串字段：缺失 / null / 类型不符一律视为未提供（🔴 不得抛异常）。 */
function optionalStr(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined
}

/** 数字字段：缺失 / null / NaN 一律视为未提供。 */
function optionalNum(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function isAbort(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError'
}
