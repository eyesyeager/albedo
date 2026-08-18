/**
 * M3 错误码展示层级与文案映射（design-system.md §14.5 / api-spec.md §2.2）。
 *
 * 🔴 纪律：
 *   1. 本文件只产出 **locale key**，字面量一律在 `locales/`，组件不得内联中文
 *   2. 能在工具节点解释的错误不升级为全局提示；只有无对应工具节点或整次生成不可继续时才用消息级块
 *   3. `timed_out` 的两种语义必须区分（api-spec §7.8.1）：
 *      - `errorCode=30050` → **确认等待超时**（语义等同拒绝，未执行）
 *      - `errorCode=30051` / `30056` → **执行超时 / 结果未知**（绝不暗示已成功）
 *   4. 一律不使用全局 Toast，避免同一 SSE 错误在状态条、消息块与 Toast 三处重复
 */
import { ERROR_CODE } from '@/types/api'
import type { ToolCallStatusValue } from '@/types/tool'

/** 错误在界面上的展示位置。 */
export type ErrorDisplayLevel = 'tool' | 'message'

export interface ErrorDisplayText {
  titleKey: string
  descriptionKey: string
}

/** 工具节点内可解释的错误码 → locale key 前缀（design-system §14.5）。 */
const TOOL_LEVEL_PREFIX: Readonly<Record<number, string>> = {
  [ERROR_CODE.TOOL_DENIED]: 'errors.toolDenied',
  [ERROR_CODE.TOOL_TIMEOUT]: 'errors.toolTimeout',
  [ERROR_CODE.MCP_UNAVAILABLE]: 'errors.mcpUnavailable',
  [ERROR_CODE.TOOL_ARGS_INVALID]: 'errors.toolArgsInvalid',
  [ERROR_CODE.TOOL_RETRY_BLOCKED]: 'errors.toolRetryBlocked',
  [ERROR_CODE.TOOL_EXECUTION_FAILED]: 'errors.toolExecutionFailed',
  [ERROR_CODE.INTERNAL_ERROR]: 'errors.toolInternal',
}

/** 只能在消息级块中表达的错误码（无对应 toolCallId 或整次生成终止）。 */
const MESSAGE_LEVEL_PREFIX: Readonly<Record<number, string>> = {
  [ERROR_CODE.TOOL_LOOP_LIMIT_EXCEEDED]: 'errors.toolLoopLimit',
  [ERROR_CODE.RUNTIME_CONFIG_INVALID]: 'errors.runtimeConfigInvalid',
  [ERROR_CODE.RATE_LIMITED]: 'errors.rateLimited',
  // 🔴 30070 只在此层解释"本条为何没发出去"；重置时刻与恢复引导由额度状态轨承担，
  //    🔴 绝不复用 errors.rateLimited（那会把"等到明天"表现成秒级倒计时）
  [ERROR_CODE.DAILY_QUOTA_EXHAUSTED]: 'errors.dailyQuotaExhausted',
}

/**
 * 工具状态条内的错误说明 key。
 *
 * @returns locale key；无需展示错误说明时返回空串
 */
export function toolErrorDescriptionKey(
  status: ToolCallStatusValue,
  errorCode: number | null,
): string {
  if (errorCode === null) {
    return ''
  }
  // 🔴 确认等待超时（timed_out + 30050）与执行超时（30051 / 30056）语义不可混淆
  if (status === 'timed_out' && errorCode === ERROR_CODE.TOOL_DENIED) {
    return 'errors.toolDenied.confirmTimeout'
  }
  const prefix = TOOL_LEVEL_PREFIX[errorCode]
  return prefix === undefined ? '' : `${prefix}.description`
}

/** 判定某错误码在当前上下文应展示在哪一层。 */
export function errorDisplayLevel(errorCode: number | null, hasToolNode: boolean): ErrorDisplayLevel {
  if (errorCode === null) {
    return 'message'
  }
  if (MESSAGE_LEVEL_PREFIX[errorCode] !== undefined) {
    return 'message'
  }
  // 工具类错误已在状态条解释；没有工具节点时必须补消息级块，否则用户看不到任何解释
  return hasToolNode && TOOL_LEVEL_PREFIX[errorCode] !== undefined ? 'tool' : 'message'
}

/**
 * 消息级错误块文案。
 *
 * @param errorCode 数字业务码（null 表示只有服务端 message 可用）
 * @param hasToolNode 本条消息是否已有工具节点承载解释
 * @returns 文案 key；返回 null 表示不需要消息级块
 */
export function messageErrorText(
  errorCode: number | null,
  hasToolNode: boolean,
): ErrorDisplayText | null {
  if (errorDisplayLevel(errorCode, hasToolNode) === 'tool') {
    return null
  }
  if (errorCode === null) {
    return null
  }
  const prefix = MESSAGE_LEVEL_PREFIX[errorCode] ?? TOOL_LEVEL_PREFIX[errorCode]
  if (prefix === undefined) {
    return null
  }
  return { titleKey: `${prefix}.title`, descriptionKey: `${prefix}.description` }
}

/**
 * 从 `ApiError.data` / SSE `error` 事件中读取剩余等待秒数（api-spec §7.12）。
 * 🔴 前端不得硬编码等待秒数，也不得自动重试。
 */
export function readRetryAfterSeconds(source: unknown): number | null {
  if (typeof source === 'number') {
    return source >= 1 ? Math.floor(source) : null
  }
  if (source === null || typeof source !== 'object') {
    return null
  }
  const value = (source as Record<string, unknown>).retryAfterSeconds
  return typeof value === 'number' && value >= 1 ? Math.floor(value) : null
}
