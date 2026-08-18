/**
 * 租户时区下的额度时间渲染（契约：docs/api-spec.md §7.15.6、design-system.md §15.4）。
 *
 * 🔴 纪律：
 *   1. `resetsAt` 是 **UTC 绝对时间**，展示必须把响应中的 IANA `timezone`
 *      原样交给 `Intl.DateTimeFormat`；🔴 **禁止**省略 `timeZone` 而用浏览器本地时区推算（K10）
 *   2. “今日 / 明日”的判断同样在**租户时区**下比较日期，
 *      🔴 不得先按浏览器本地日期判断再只格式化时分
 *   3. 🔴 不把 `00:00` 写死：DST 使当地日首为 `01:00` 时必须如实显示 `01:00`
 *      （因此本文件不含任何固定时刻常量，也不做 24h/86400s 推算）
 *   4. 时区非法时返回 null（🔴 绝不回落浏览器时区）：调用方省略重置段，
 *      发送准入仍由服务端裁决（后端对非法 timezone 返回 50003）
 */
import { getLocale } from '@/locales'

/** `resetsAt` 相对“今天”的租户本地日关系。 */
export type TenantDayRelation = 'today' | 'tomorrow' | 'later'

/** 一个租户本地日的毫秒基数（🔴 只用于比较两个**日期键**，不用于推算额度日长度）。 */
const DAY_KEY_MS = 24 * 60 * 60 * 1000

/**
 * 按租户时区格式化时刻（HH:mm，24 小时制）。
 *
 * @returns `01:00` 这样的字符串；时区或时间非法时返回 null
 */
export function formatTenantClock(instantIso: string, timezone: string): string | null {
  const date = toDate(instantIso)
  if (date === null) {
    return null
  }
  try {
    return new Intl.DateTimeFormat(getLocale(), {
      timeZone: timezone,
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    }).format(date)
  } catch {
    // 非法 IANA 时区：🔴 不回落浏览器时区（静默错误比失败更糟）
    return null
  }
}

/**
 * 按租户时区格式化日期（用于“既非今日也非明日”的兜底展示）。
 */
export function formatTenantDate(instantIso: string, timezone: string): string | null {
  const date = toDate(instantIso)
  if (date === null) {
    return null
  }
  try {
    return new Intl.DateTimeFormat(getLocale(), {
      timeZone: timezone,
      month: 'numeric',
      day: 'numeric',
    }).format(date)
  } catch {
    return null
  }
}

/**
 * 判断 `resetsAt` 落在租户本地的今日 / 明日 / 更晚。
 *
 * @param nowMs 当前时刻（毫秒，便于测试注入）
 */
export function tenantDayRelation(
  instantIso: string,
  timezone: string,
  nowMs: number = Date.now(),
): TenantDayRelation | null {
  const target = tenantDayKey(instantIso, timezone)
  const today = tenantDayKey(new Date(nowMs).toISOString(), timezone)
  if (target === null || today === null) {
    return null
  }
  const diffDays = Math.round((target - today) / DAY_KEY_MS)
  if (diffDays <= 0) {
    return 'today'
  }
  return diffDays === 1 ? 'tomorrow' : 'later'
}

/**
 * 该时刻在租户时区下的“本地日期”键（以 UTC 零点表示，仅用于日期比较）。
 * 🔴 用 `formatToParts` 逐字段取值，避免依赖某个 locale 的日期串格式。
 */
function tenantDayKey(instantIso: string, timezone: string): number | null {
  const date = toDate(instantIso)
  if (date === null) {
    return null
  }
  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: timezone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(date)
    const year = partValue(parts, 'year')
    const month = partValue(parts, 'month')
    const day = partValue(parts, 'day')
    if (year === null || month === null || day === null) {
      return null
    }
    return Date.UTC(year, month - 1, day)
  } catch {
    return null
  }
}

function partValue(parts: readonly Intl.DateTimeFormatPart[], type: string): number | null {
  const found = parts.find((part) => part.type === type)
  if (found === undefined) {
    return null
  }
  const value = Number.parseInt(found.value, 10)
  return Number.isFinite(value) ? value : null
}

function toDate(instantIso: string): Date | null {
  const ms = Date.parse(instantIso)
  return Number.isFinite(ms) ? new Date(ms) : null
}
