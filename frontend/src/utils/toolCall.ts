/**
 * 工具调用状态归一与合并（契约：docs/api-spec.md §5.2 / §5.4.1、design-system.md §14.1～§14.2）。
 *
 * 🔴 纪律：
 *   1. **按 `toolCallId` 原位更新**：同一调用的多帧只更新既有条目，绝不每帧重建列表
 *   2. 未知字段 / 未知枚举 / 字段缺失一律容忍（M1 行为不得退化）
 *   3. 摘要只用后端脱敏值，前端不做二次截断、不展示阈值数字
 */
import {
  isToolRiskLevel,
  type RawToolCall,
  type ToolCallSummary,
  type ToolRiskLevel,
  type ToolType,
} from '@/types/tool'

/** 归一：把可能缺字段 / 含 null 的原始载荷收敛为确定形态。 */
export function normalizeToolCall(raw: RawToolCall): ToolCallSummary {
  return {
    toolCallId: text(raw.toolCallId),
    toolType: toolType(raw.toolType),
    toolKey: text(raw.toolKey),
    // 🔴 未知状态原样保留（渲染层降级），不猜测、不丢帧
    status: text(raw.status),
    riskLevel: riskLevel(raw.riskLevel),
    round: positiveInt(raw.round),
    summary: text(raw.summary),
    argsSummary: text(raw.argsSummary),
    resultSummary: text(raw.resultSummary),
    truncated: raw.truncated === true,
    errorCode: typeof raw.errorCode === 'number' ? raw.errorCode : null,
    retryAfterSeconds: typeof raw.retryAfterSeconds === 'number' ? raw.retryAfterSeconds : null,
    // 🔴 只做形态归一（非有限数 / 缺失 → null），是否可用由 resolveConfirmWaitSeconds 判定
    confirmExpiresInSeconds: finiteOrNull(raw.confirmExpiresInSeconds),
  }
}

/**
 * 确认倒计时**总时长**的取值（api-spec §5.2 `confirmExpiresInSeconds` 前端消费规则）。
 *
 * 🔴 优先级（唯一实现，禁止在组件 / Store 里再写一套）：
 *   ① 帧内 `confirmExpiresInSeconds` 为正数 → 用它（服务端按剩余生成预算收紧后的**真实**剩余秒数）
 *   ② 缺失 / `null` / 非正数（旧后端、历史回显、异常值）→ 回退全局 `sys_config: tool.confirmWaitSeconds`
 *
 * 🔴 ① 不违反"绝不用替代秒数兜底"纪律：该值来自服务端本帧，不是前端内置默认值；
 *    因此即便全局配置未下发（fallback=0），只要本帧给了正数就照它倒计时（显示真话优于不显示）。
 *
 * @param fallbackSeconds 全局等待上限；≤0 表示未下发 → 返回 ≤0 即"不渲染倒计时"
 * @returns 倒计时总秒数（整秒）；≤0 表示不可用
 */
export function resolveConfirmWaitSeconds(
  call: Pick<ToolCallSummary, 'confirmExpiresInSeconds'>,
  fallbackSeconds: number,
): number {
  const fromFrame = call.confirmExpiresInSeconds
  if (typeof fromFrame === 'number' && Number.isFinite(fromFrame) && fromFrame > 0) {
    return Math.floor(fromFrame)
  }
  return fallbackSeconds
}

/**
 * 原位合并一帧工具事件。
 *
 * 合并规则（保证 M1 只发 summary 的旧帧与 M3 完整帧都能正确收敛）：
 *   - `status` 始终取新帧（状态迁移是本函数存在的理由）
 *   - 摘要 / toolKey / riskLevel / round 为空时**沿用旧值**，避免被空帧擦除
 *   - `errorCode` 取新帧，缺失时沿用旧值（终态语义不可丢）
 */
export function upsertToolCall(
  existing: readonly ToolCallSummary[],
  incoming: ToolCallSummary,
): ToolCallSummary[] {
  const index = existing.findIndex((item) => item.toolCallId === incoming.toolCallId)
  if (index < 0) {
    return [...existing, incoming]
  }
  const previous = existing[index]
  const merged: ToolCallSummary = {
    toolCallId: previous.toolCallId,
    toolType: incoming.toolType,
    toolKey: incoming.toolKey.length > 0 ? incoming.toolKey : previous.toolKey,
    status: incoming.status.length > 0 ? incoming.status : previous.status,
    riskLevel: incoming.riskLevel.length > 0 ? incoming.riskLevel : previous.riskLevel,
    round: incoming.round > 0 ? incoming.round : previous.round,
    summary: incoming.summary.length > 0 ? incoming.summary : previous.summary,
    argsSummary: incoming.argsSummary.length > 0 ? incoming.argsSummary : previous.argsSummary,
    resultSummary:
      incoming.resultSummary.length > 0 ? incoming.resultSummary : previous.resultSummary,
    truncated:
      incoming.truncated || (incoming.resultSummary.length === 0 && previous.truncated),
    errorCode: incoming.errorCode ?? previous.errorCode,
    retryAfterSeconds: incoming.retryAfterSeconds ?? previous.retryAfterSeconds,
    // 🔴 与 errorCode / retryAfterSeconds 同口径：新帧为 null（终态帧恒 null）时沿用旧值，
    //    保证 pending → awaiting 拿到的剩余秒数不会被后续帧擦除（Store 只在首帧登记截止时刻）
    confirmExpiresInSeconds:
      incoming.confirmExpiresInSeconds ?? previous.confirmExpiresInSeconds ?? null,
  }
  // 🔴 原位替换：数组顺序即 SSE 到达顺序，不得因状态更新改变 DOM 顺序
  const next = [...existing]
  next[index] = merged
  return next
}

/** 按 `round` 分组，组内与组间均保持首次出现顺序（design-system §14.1）。 */
export interface ToolCallRoundGroup {
  round: number
  calls: ToolCallSummary[]
}

export function groupToolCallsByRound(calls: readonly ToolCallSummary[]): ToolCallRoundGroup[] {
  const groups: ToolCallRoundGroup[] = []
  calls.forEach((call) => {
    const group = groups.find((item) => item.round === call.round)
    if (group === undefined) {
      groups.push({ round: call.round, calls: [call] })
      return
    }
    group.calls.push(call)
  })
  return groups
}

/** 仅当出现两个及以上**已知**轮次时才显示轮次标签（design-system §14.1）。 */
export function shouldShowRoundLabel(groups: readonly ToolCallRoundGroup[]): boolean {
  return groups.filter((group) => group.round > 0).length >= 2
}

/**
 * 解析 `sys_config` 下发的展示标签。
 *
 * 兼容两种下发形态（🔴 契约未固定，二者都必须能用）：
 *   ① `{ "high": "高风险" }`
 *   ② `[{ "value": "high", "label": "高风险" }]`
 *
 * @returns 命中时返回文案，未命中返回空串（调用方回退 locales，绝不显示原始枚举值）
 */
export function resolveConfigLabel(source: unknown, value: string): string {
  if (value.length === 0 || source === null || source === undefined) {
    return ''
  }
  if (Array.isArray(source)) {
    for (const item of source) {
      if (item === null || typeof item !== 'object') {
        continue
      }
      const entry = item as Record<string, unknown>
      if (entry.value === value && typeof entry.label === 'string') {
        return entry.label
      }
    }
    return ''
  }
  if (typeof source === 'object') {
    const label = (source as Record<string, unknown>)[value]
    return typeof label === 'string' ? label : ''
  }
  return ''
}

/** 可展开摘要分区（design-system §14.2.3）。 */
export interface ToolSummarySection {
  /** locale key（🔴 组件内不得内联中文字面量） */
  labelKey: string
  text: string
}

/**
 * 计算可展开内容：
 *   - `pending` / `awaiting_confirmation` / `running` → 参数摘要
 *   - `succeeded` → 结果摘要
 *   - `failed` / `timed_out` → 结果摘要优先，缺失时退回参数摘要
 *   - `cancelled` / `denied` → 仅在服务端给出非空结果摘要时才可展开
 *   - 未知状态 → 保守地只展示已有的结果 / 参数摘要之一
 */
export function toolSummarySections(call: ToolCallSummary): ToolSummarySection[] {
  const args: ToolSummarySection = { labelKey: 'chat.toolCall.argsLabel', text: call.argsSummary }
  const result: ToolSummarySection = {
    labelKey: 'chat.toolCall.resultLabel',
    text: call.resultSummary,
  }
  switch (call.status) {
    case 'pending':
    case 'awaiting_confirmation':
    case 'running':
      return nonEmpty([args])
    case 'succeeded':
      return nonEmpty([result])
    case 'failed':
    case 'timed_out':
      return nonEmpty(result.text.length > 0 ? [result] : [args])
    case 'cancelled':
    case 'denied':
      return nonEmpty([result])
    default:
      return nonEmpty(result.text.length > 0 ? [result] : [args])
  }
}

function nonEmpty(sections: readonly ToolSummarySection[]): ToolSummarySection[] {
  return sections.filter((section) => section.text.length > 0)
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function toolType(value: unknown): ToolType {
  return value === 'local' ? 'local' : 'mcp'
}

function riskLevel(value: unknown): ToolRiskLevel | '' {
  return isToolRiskLevel(value) ? value : ''
}

function positiveInt(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0
}

/** 可空数值归一：非有限数 / 缺失 → null（不猜测、不兜底）。 */
function finiteOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}
