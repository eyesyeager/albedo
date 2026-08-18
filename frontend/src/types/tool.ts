/**
 * M3 工具编排类型（契约：docs/api-spec.md §5.2 / §5.4 / §7.8 / §7.9）。
 *
 * 🔴 向后兼容硬约束（api-spec §5.4.1）：
 *   1. 枚举只允许追加，未知取值**不得**让流崩溃 —— 因此 `status` / `riskLevel`
 *      在传输层保持宽类型，由 `isToolCallStatus` / `isToolRiskLevel` 收窄后再决定渲染
 *   2. M3 新增字段允许为 null，也允许整体缺失 → 统一由 `normalizeToolCall` 归一
 *   3. 事件名集合固定为 meta/delta/tool/error/done，本文件不新增事件名
 *
 * 🔴 禁止 any；🔴 所有 id 为 string（BIGINT 精度保护，ADR-004）。
 */

/** 工具调用状态（api-spec §5.2 / §7.8.1，snake_case 为实现基线）。 */
export type ToolCallStatus =
  | 'pending'
  | 'awaiting_confirmation'
  | 'running'
  | 'succeeded'
  | 'failed'
  | 'timed_out'
  | 'cancelled'
  | 'denied'

/**
 * 传输层状态类型：已知 8 态 + 容忍未知字符串。
 * `(string & {})` 保留已知枚举的编辑器提示，同时不拒绝后端未来追加的取值。
 */
export type ToolCallStatusValue = ToolCallStatus | (string & {})

/** 全部已知状态（渲染与测试的唯一来源）。 */
export const TOOL_CALL_STATUSES: readonly ToolCallStatus[] = [
  'pending',
  'awaiting_confirmation',
  'running',
  'succeeded',
  'failed',
  'timed_out',
  'cancelled',
  'denied',
]

/** 终态（api-spec §7.8.1）：不再迁移，可作为埋点上报与倒计时取消的判据。 */
export const TERMINAL_TOOL_STATUSES: readonly ToolCallStatus[] = [
  'succeeded',
  'failed',
  'timed_out',
  'cancelled',
  'denied',
]

export function isToolCallStatus(value: unknown): value is ToolCallStatus {
  return typeof value === 'string' && TOOL_CALL_STATUSES.includes(value as ToolCallStatus)
}

export function isTerminalToolStatus(value: ToolCallStatusValue): boolean {
  return TERMINAL_TOOL_STATUSES.includes(value as ToolCallStatus)
}

/** 工具类型（api-spec §5.2）。 */
export type ToolType = 'local' | 'mcp'

/** 风险等级；展示文案取 `sys_config: display.toolRiskLabels`，🔴 前端不得硬编码。 */
export type ToolRiskLevel = 'low' | 'medium' | 'high'

export const TOOL_RISK_LEVELS: readonly ToolRiskLevel[] = ['low', 'medium', 'high']

export function isToolRiskLevel(value: unknown): value is ToolRiskLevel {
  return typeof value === 'string' && TOOL_RISK_LEVELS.includes(value as ToolRiskLevel)
}

/** 用户对高风险工具的决定（api-spec §7.8.2）。 */
export type ToolConfirmDecision = 'allow' | 'deny'

/**
 * 服务端下发的**原始**工具调用载荷。
 * 🔴 字段可能缺失或为 null（M1 前端兼容 + M3 新增字段可空），一律经 `normalizeToolCall` 归一后再使用。
 */
export interface RawToolCall {
  toolCallId?: string
  toolType?: string
  toolKey?: string
  riskLevel?: string
  status?: string
  round?: number
  summary?: string
  argsSummary?: string
  resultSummary?: string
  truncated?: boolean
  errorCode?: number | null
  retryAfterSeconds?: number | null
  /**
   * 本次确认的**实际剩余等待秒数**（api-spec §5.2，V1.2.2 新增）。
   *
   * 🔴 仅 `status=awaiting_confirmation` 帧非空，其余状态恒 `null`（`JsonInclude.ALWAYS`）；
   * 历史回显（§4.5.6）不带该语义，故恒 `null`。
   * 🔴 字段可整体缺失（旧后端 / 代理裁字段），因此声明为可选 —— 缺失与 `null` 同义。
   */
  confirmExpiresInSeconds?: number | null
}

/**
 * 归一后的工具调用视图对象：SSE `tool` 事件与 §7.9 查询结果的公共可展示子集。
 *
 * 🔴 只包含**已脱敏摘要**（api-spec §5.4.3）：不含完整入参 / 完整结果 / endpoint / 凭据。
 */
export interface ToolCallSummary {
  toolCallId: string
  toolType: ToolType
  toolKey: string
  /** 🔴 保持宽类型以容忍未知枚举（§5.4.1），渲染前用 isToolCallStatus 收窄 */
  status: ToolCallStatusValue
  /** 未知或缺失时为空串 → 不渲染风险标签（优雅降级，不猜测风险） */
  riskLevel: ToolRiskLevel | ''
  /** 第几轮工具调用，从 1 开始；缺失为 0（视为轮次未知，不显示轮次标签） */
  round: number
  /** 🔴 兼容字段，永久保留（§5.2）：当前阶段可展示摘要 */
  summary: string
  argsSummary: string
  resultSummary: string
  /** 结果是否被后端截断（EX-017）；🔴 前端不做二次截断、不展示阈值数字 */
  truncated: boolean
  errorCode: number | null
  retryAfterSeconds: number | null
  /**
   * 本次确认的实际剩余等待秒数（api-spec §5.2，V1.2.2）。
   *
   * 🔴 存在理由：确认等待上限被**单次生成总预算**收紧为
   * `min(sys_config: tool.confirmWaitSeconds, 剩余预算 − 宽限)`（ADR-017 ③ⓑ），
   * 前端若继续按全局 `confirmWaitSeconds` 显示倒计时就会骗人（显示 120s 而 30s 后即 `timed_out`）。
   * 🔴 消费规则收口在 `resolveConfirmWaitSeconds`：本字段优先，`null` / 缺失才回退全局配置。
   * 🔴 声明为可选而非必填：旧后端与历史回显都不带该字段，缺失即 `undefined`，与 `null` 同义。
   */
  confirmExpiresInSeconds?: number | null
}

/** 确认接口响应（api-spec §7.8.2）。 */
export interface ToolConfirmResult {
  toolCallId: string
  messageId: string
  decision: ToolConfirmDecision
  status: ToolCallStatusValue
  decidedAt: string
  /** 重复提交同一决定时为 true → 🔴 静默回放，不提示用户 */
  replayed: boolean
}

/** 确认请求体（api-spec §7.8.2；🔴 不使用 Idempotency-Key）。 */
export interface ToolConfirmPayload {
  decision: ToolConfirmDecision
  /** 拒绝原因，≤200 字符；🔴 禁含敏感值 */
  reason?: string
}
