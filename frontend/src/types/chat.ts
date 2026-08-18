/**
 * 会话与消息类型（契约：docs/api-spec.md §4.5 / §4.6）。
 *
 * 🔴 所有 id 一律 string（ADR-004）；🔴 禁止 any。
 */
import type { AssistantFinalStatus, FinishReason } from '@/utils/streamRequest'
import type { RawToolCall, ToolCallSummary } from '@/types/tool'

/** 工具调用类型统一从 `types/tool.ts` 导出，避免出现第二套定义。 */
export type { RawToolCall, ToolCallSummary } from '@/types/tool'

/** 会话状态（PRD §10.4）。 */
export type ConversationStatus = 'active' | 'readOnly' | 'deleted'

/** 标题来源：manual 之后自动标题不得覆盖（AC-CON-004）。 */
export type TitleSource = 'auto' | 'model' | 'manual'

/** 会话对象（api-spec §4.5.1，含乐观锁 version）。 */
export interface Conversation {
  conversationId: string
  title: string
  titleSource: TitleSource
  agentId: string
  agentVersion: number
  status: ConversationStatus
  messageCount: number
  lastMessageAt: string | null
  updatedAt: string
  version: number
}

/** 用户可见消息角色（🔴 system / tool 不下发，PRD §8.8）。 */
export type MessageRole = 'user' | 'assistant'

/** 消息状态机（PRD §8.8）。 */
export type MessageStatus =
  | 'pending'
  | 'sent'
  | 'queued'
  | 'streaming'
  | 'completed'
  | 'stopped'
  | 'failed'

export interface TokenUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

/** 工具调用摘要（M1 恒为空，M3 填充；🔴 服务端字段可能缺失，读取前必须归一）。 */

/**
 * 一轮生成产出的「思考 / 正文」段落（api-spec §4.5.6 `segments`）。
 *
 * 🔴 `round` 之后紧跟 `toolCalls` 中 `round` 相同的工具调用，
 * 由此还原「思考① → 工具① → 思考② → 正文」的真实时序。
 */
export interface MessageRoundSegment {
  round: number
  reasoning: string
  text: string
}

/** 消息对象（api-spec §4.5.6）。 */
export interface ChatMessage {
  messageId: string
  role: MessageRole
  content: string
  /**
   * 思考过程（仅 assistant，可空）。
   * 🔴 服务端可能缺省该字段（user 消息 / 无思考过程 / 旧数据），读取前必须归一为空串。
   */
  reasoning?: string | null
  status: MessageStatus
  attemptNo: number
  isCurrent: boolean
  model?: string
  agentVersion?: number
  finishReason?: FinishReason | string
  tokenUsage?: TokenUsage
  /** 🔴 原始载荷：字段可缺可空，一律经 `normalizeToolCall` 归一后再渲染 */
  toolCalls?: RawToolCall[]
  /**
   * 按轮次的段落（仅 assistant，可空）。
   * 🔴 缺失时前端**降级为旧版布局**（全部思考 → 全部工具 → 全部正文），只损失排版不丢内容。
   */
  segments?: MessageRoundSegment[] | null
  createdAt: string
}

/**
 * 消息视图模型：在接口字段之外附加**纯客户端态**。
 *
 * `clientId` 让流式期间（messageId 尚未到达）列表 key 依然稳定，
 * 避免 DOM 重建导致的动画重放与滚动跳动。
 */
export interface ChatMessageView extends ChatMessage {
  clientId: string
  streaming: boolean
  errorMessage: string | null
  /** 本次尝试的数字业务码（SSE `error` 事件 / 流未建立的 JSON 错误），用于错误码分层展示 */
  errorCode: number | null
  /** 归一后的工具调用（🔴 按 toolCallId 原位更新，顺序即 SSE 到达顺序） */
  toolCalls: ToolCallSummary[]
  /**
   * 推理型模型的思考过程（流式期间由 `delta.reasoning` 累积，历史由 `messages.reasoning` 回填）。
   *
   * 🔴 已归一为空串（服务端可能返回 null / 省略字段），组件可直接 `.length` 判空。
   * 🔴 绝不与 `content` 混用 —— 后者是答案正文，参与复制、标题生成与上下文回灌。
   */
  reasoning: string
  /**
   * 按轮次段落（流式期间实时构建，历史由 `messages.segments` 回填）。
   *
   * 🔴 已归一为数组（服务端可能返回 null / 省略字段）。
   * 🔴 空数组表示「无时序信息」→ 渲染层降级为旧版布局（全部思考 → 全部工具 → 全部正文）。
   */
  segments: MessageRoundSegment[]
}

/** 停止生成响应（api-spec §4.6.2）。 */
export interface StopResult {
  messageId: string
  status: AssistantFinalStatus
}

/** 一次发送请求的可重放上下文（幂等键必须复用，EX-013）。 */
export interface PendingSend {
  content: string
  agentId: string | null
  idempotencyKey: string
  userClientId: string
  assistantClientId: string
}
