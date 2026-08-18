/**
 * 额度快照解析与派生（契约：docs/api-spec.md §7.15.2、design-system.md §15.1.1）。
 *
 * 🔴 纪律：
 *   1. 快照**只信服务端**：本文件不含任何额度默认值（🔴 无 50、无 3、无兜底 limit）
 *   2. 9 键缺一即视为非法形态 → 返回 null，🔴 绝不用局部字段拼一个"看起来正常"的快照
 *   3. `30070` 的 `data` 与查询响应**同形**，因此复用同一个解析入口（🔴 不写第二套）
 *   4. 🔴 展示等级只影响视觉，不参与业务准入：能否发送恒由服务端裁决
 */
import { ERROR_CODE } from '@/types/api'
import {
  QUOTA_SNAPSHOT_KEYS,
  type QuotaDisplayState,
  type QuotaSnapshot,
  type QuotaStatus,
  type QuotaView,
} from '@/types/quota'
import { ApiError } from '@/utils/request'
import { QUOTA_LOW_RATIO } from '@/utils/uiConstants'

const QUOTA_STATUSES: readonly QuotaStatus[] = ['available', 'exhausted', 'unlimited']

/**
 * 解析额度快照。
 *
 * @param source 服务端 `data`（成功响应或 `30070` 载荷）
 * @returns 合法快照；形态非法时返回 null（调用方按"额度暂不可用"处理）
 */
export function parseQuotaSnapshot(source: unknown): QuotaSnapshot | null {
  if (source === null || typeof source !== 'object') {
    return null
  }
  const raw = source as Record<string, unknown>
  // 🔴 9 键逐一必须存在：缺键说明后端契约不一致，前端不得静默补默认值
  if (QUOTA_SNAPSHOT_KEYS.some((key) => !(key in raw))) {
    return null
  }
  const { enabled, limit, used, remaining, status, periodStart, resetsAt, timezone, asOf } = raw
  if (typeof enabled !== 'boolean' || typeof used !== 'number') {
    return null
  }
  if (!isNullableNumber(limit) || !isNullableNumber(remaining)) {
    return null
  }
  if (
    !isNonEmptyString(status) ||
    !isNonEmptyString(periodStart) ||
    !isNonEmptyString(resetsAt) ||
    !isNonEmptyString(timezone) ||
    !isNonEmptyString(asOf)
  ) {
    return null
  }
  return {
    enabled,
    limit,
    used,
    remaining,
    // 未知枚举值不中断展示：按 enabled / remaining 归一（§7.15.2 三者恒一致）
    status: QUOTA_STATUSES.includes(status as QuotaStatus)
      ? (status as QuotaStatus)
      : deriveStatus(enabled, remaining),
    periodStart,
    resetsAt,
    timezone,
    asOf,
  }
}

/**
 * 从异常中读取日额度用尽快照（`30070`）。
 *
 * 🔴 只认 `30070`：`10005` 的 `retryAfterSeconds` 归 `rateLimitStore`，两者不得互相消费。
 *
 * @returns 命中 `30070` 且载荷合法时返回快照，否则 null
 */
export function readQuotaSnapshotFromError(error: unknown): QuotaSnapshot | null {
  if (!(error instanceof ApiError) || error.code !== ERROR_CODE.DAILY_QUOTA_EXHAUSTED) {
    return null
  }
  return parseQuotaSnapshot(error.data)
}

/**
 * 日额度是否已用尽（🔴 派生自权威快照，用于禁用发送）。
 *
 * 判据取 `status` 与 `remaining` 的并集：后端保证两者一致，取并集可在
 * 任一字段异常时仍然 fail-closed（宁可多禁一次，也不给用户一个点不动的按钮）。
 */
export function isQuotaExhausted(snapshot: QuotaSnapshot | null): boolean {
  if (snapshot === null || !snapshot.enabled) {
    return false
  }
  return snapshot.status === 'exhausted' || snapshot.remaining === 0
}

/**
 * 计算状态轨展示态（design-system.md §15.1.1）。
 *
 * 🔴 优先级：hidden → exhausted → rateLimited → unlimited → loading/unavailable → lastOne → low → normal
 * 🔴 不写死平台默认 50 或任何租户限额：偏低只由 `remaining / limit` 与展示常量决定。
 *
 * @param view 额度视图（阶段 + 快照）
 * @param rateLimitRemaining QPM 剩余等待秒数（只来自服务端 `retryAfterSeconds`）
 */
export function resolveQuotaDisplayState(
  view: QuotaView,
  rateLimitRemaining: number,
): QuotaDisplayState {
  if (view.phase === 'hidden') {
    return 'hidden'
  }
  const snapshot = view.snapshot
  // 🔴 日额度用尽优先于 QPM：当日不可恢复的状态不得被秒级倒计时覆盖（AC-QUOTA-012）
  if (isQuotaExhausted(snapshot)) {
    return 'exhausted'
  }
  if (rateLimitRemaining > 0) {
    return 'rateLimited'
  }
  if (snapshot === null) {
    return view.phase === 'unavailable' ? 'unavailable' : 'loading'
  }
  if (!snapshot.enabled) {
    return 'unlimited'
  }
  const { limit, remaining } = snapshot
  if (remaining === null || limit === null || limit <= 0) {
    return 'normal'
  }
  if (remaining === 1) {
    return 'lastOne'
  }
  return remaining / limit <= QUOTA_LOW_RATIO ? 'low' : 'normal'
}

function deriveStatus(enabled: boolean, remaining: number | null): QuotaStatus {
  if (!enabled) {
    return 'unlimited'
  }
  return remaining === 0 ? 'exhausted' : 'available'
}

function isNullableNumber(value: unknown): value is number | null {
  return value === null || typeof value === 'number'
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}
