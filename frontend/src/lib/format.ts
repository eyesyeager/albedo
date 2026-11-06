const TZ = 'Asia/Shanghai'

function parts(iso: string): Intl.DateTimeFormatPart[] {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: TZ,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).formatToParts(new Date(iso))
}

function pick(iso: string): Record<string, string> {
  return Object.fromEntries(parts(iso).filter((p) => p.type !== 'literal').map((p) => [p.type, p.value]))
}

export function formatTime(iso: string): string {
  const p = pick(iso)
  return `${p.hour}:${p.minute}`
}

export function formatDate(iso: string): string {
  const p = pick(iso)
  return `${p.month}-${p.day}`
}

export function formatDateTime(iso: string): string {
  const p = pick(iso)
  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}`
}

export function formatMonthDay(iso: string): string {
  const p = pick(iso)
  return `${Number(p.month)} 月 ${Number(p.day)} 日`
}

export function formatRelative(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  return formatDateTime(iso)
}

export function formatDuration(ms?: number): string {
  if (ms === undefined || ms === null) return '—'
  if (ms < 1000) return `${Math.round(ms)} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)} s`
  return `${(ms / 1000).toFixed(0)} s`
}

export function formatNumber(value: number): string {
  return new Intl.NumberFormat('zh-CN').format(Math.round(value))
}

export function formatFileSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`
  return `${bytes} B`
}

export function daysAgoIso(days: number, hours = 0): string {
  return new Date(Date.now() - days * 86_400_000 - hours * 3_600_000).toISOString()
}

export function isWithinDays(iso: string, days: number): boolean {
  return Date.now() - new Date(iso).getTime() <= days * 86_400_000
}

export function isToday(iso: string): boolean {
  return formatDate(iso) === formatDate(new Date().toISOString())
}

export function formatPercent(value: number, digits = 1): string {
  return `${value.toFixed(digits)}%`
}
