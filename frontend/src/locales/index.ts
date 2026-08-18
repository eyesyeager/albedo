/**
 * 极简 i18n：只服务「静态 UI 文案」，不承载任何业务可变内容。
 *
 * 用法：
 * ```ts
 * import { t } from '@/locales'
 * t('common.loadFailed')
 * t('chat.inputTooLong', { max: 20000 })   // 支持 {max} 占位符插值
 * ```
 *
 * 🔴 禁止把租户文案 / 业务枚举放进来（见 zh-CN.ts 顶部纪律说明）。
 */
import zhCN from './zh-CN'

export type LocaleKey = 'zh-CN'

const MESSAGES: Record<LocaleKey, unknown> = {
  'zh-CN': zhCN,
}

let currentLocale: LocaleKey = 'zh-CN'

export function setLocale(locale: LocaleKey): void {
  currentLocale = locale
}

export function getLocale(): LocaleKey {
  return currentLocale
}

/**
 * 取文案。找不到时返回 key 本身（便于开发期发现缺失，不静默显示空白）。
 */
export function t(path: string, params?: Record<string, string | number>): string {
  const raw = resolve(MESSAGES[currentLocale], path)
  if (typeof raw !== 'string') {
    return path
  }
  if (params === undefined) {
    return raw
  }
  return raw.replace(/\{(\w+)\}/g, (match, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : match,
  )
}

function resolve(source: unknown, path: string): unknown {
  return path.split('.').reduce<unknown>((acc, segment) => {
    if (acc !== null && typeof acc === 'object' && segment in (acc as Record<string, unknown>)) {
      return (acc as Record<string, unknown>)[segment]
    }
    return undefined
  }, source)
}
