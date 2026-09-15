/**
 * M3 工具编排类型（契约：docs/api-spec.md §5.2 / §5.4 / §7.8 / §7.9）。
 *
 * 🔴 向后兼容硬约束（api-spec §5.4.1）：
 *   1. 枚举只允许追加，未知取值**不得**让流崩溃 —— 因此 `status` 在传输层保持宽类型，
 *      由 `isToolCallStatus` 收窄后再决定渲染
 *   2. M3 新增字段允许为 null，也允许整体缺失 → 统一由 `normalizeToolCall` 归一
 *   3. 事件名集合固定为 meta/delta/tool/error/done，本文件不新增事件名
 *
 * 🔴 禁止 any；🔴 所有 id 为 string（BIGINT 精度保护，ADR-004）。
 */

/** 工具调用状态（api-spec §5.2 / §7.8.1，snake_case 为实现基线）。 */
export type ToolCallStatus =
  | 'pending'
  | 'running'
  | 'succeeded'
  | 'failed'
  | 'timed_out'
  | 'cancelled'
  | 'denied'

/**
 * 传输层状态类型：已知 7 态 + 容忍未知字符串。
 * `(string & {})` 保留已知枚举的编辑器提示，同时不拒绝后端未来追加的取值。
 */
export type ToolCallStatusValue = ToolCallStatus | (string & {})

/** 全部已知状态（渲染与测试的唯一来源）。 */
export const TOOL_CALL_STATUSES: readonly ToolCallStatus[] = [
  'pending',
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

/**
 * 服务端下发的**原始**工具调用载荷。
 * 🔴 字段可能缺失或为 null（M1 前端兼容 + M3 新增字段可空），一律经 `normalizeToolCall` 归一后再使用。
 */
export interface RawToolCall {
  toolCallId?: string
  toolType?: string
  toolKey?: string
  status?: string
  round?: number
  summary?: string
  argsSummary?: string
  resultSummary?: string
  truncated?: boolean
  errorCode?: number | null
  retryAfterSeconds?: number | null
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
}
