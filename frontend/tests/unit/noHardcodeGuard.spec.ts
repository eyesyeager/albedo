import { readFileSync, readdirSync } from 'node:fs'
import { extname, join, relative, resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { t } from '@/locales'

/**
 * 零硬编码 / 渲染纪律**静态扫描**守护（design-system.md §14.9.1、框架 §十四）。
 *
 * 为什么必须是源码扫描：D-007 的教训是「模板漏 import 组件」只在运行时暴露，
 * `vue-tsc` 与构建都拦不住；同理「硬编码阈值 / 内联中文 / 动画 height」
 * 也不会让任何用例变红 —— 只能靠扫描把纪律固化成可执行断言。
 * 风格对齐 @后端 的静态扫描测试。
 *
 * 🔴 守护清单：
 *   1. `.vue` 内**零中文字面量**（业务文案走 sys_config，静态文案走 locales）
 *   2. 模板中使用的组件必须已 import（D-007 类静默不渲染）
 *   3. 动效只用 Token；禁止 `transition: all`、禁止动画 height/width/top/left/margin/padding
 *   4. M3 阈值（确认秒数、批量上限、白名单）兜底必须为"空"，🔴 绝不内置业务默认值
 *   5. 摘要截断只服从后端 `truncated`，前端不得自行按字符阈值截断
 *   6. 鉴权只有一套口径；无 `router.push('/login')`、无废弃码 10002 / 40001
 *   7. 无 `console.log` 残留、无 `any`
 */
const SRC = resolve(__dirname, '../../src')

const CJK = /[\u4e00-\u9fa5]/
/** M3 交付的源文件（本轮 owner 范围；M1 遗留另行报备，不在本 spec 收口） */
const M3_FILES = [
  'components/chat/ToolCallBar.vue',
  'components/chat/ToolCallTimeline.vue',
  'components/chat/ToolConfirmCard.vue',
  'components/chat/ToolConfirmCountdown.vue',
  'components/chat/RateLimitNote.vue',
  'components/chat/MessageErrorBlock.vue',
  'components/chat/MessageItem.vue',
  'components/chat/MessageList.vue',
  'components/chat/ChatComposer.vue',
  // M3.1 每日额度（design-system §15）
  'components/chat/QuotaStatusRail.vue',
  'stores/chat.ts',
  'stores/toolConfirm.ts',
  'stores/rateLimit.ts',
  'stores/quota.ts',
  'utils/toolCall.ts',
  'utils/toolErrors.ts',
  'utils/toolVisual.ts',
  'utils/quotaSnapshot.ts',
  'utils/quotaTime.ts',
  'utils/analytics.ts',
  'utils/chatAnalytics.ts',
  'utils/chatStreamHandler.ts',
  'utils/streamRequest.ts',
  'composables/useCountdown.ts',
]

const VUE_BUILTIN = new Set([
  'Transition',
  'TransitionGroup',
  'KeepAlive',
  'Teleport',
  'Suspense',
  'Component',
  'RouterView',
  'RouterLink',
])

function walk(dir: string, exts: readonly string[]): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      return walk(full, exts)
    }
    return exts.includes(extname(entry.name)) ? [full] : []
  })
}

const VUE_FILES = walk(SRC, ['.vue'])
const TS_FILES = walk(SRC, ['.ts'])

function read(file: string): string {
  return readFileSync(file, 'utf8')
}

function rel(file: string): string {
  return relative(SRC, file)
}

/** 去掉 HTML / JS / CSS 注释，避免中文注释被误判为内联文案。 */
function stripComments(source: string): string {
  return source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:'"`\\])\/\/[^\n]*/g, '$1')
}

/**
 * 去掉 `console.error` / `console.warn` 调用。
 * 理由：开发者日志允许中文（不面向终端用户、不进 UI），
 * 但 UI 文案必须走 locales —— 两者必须分开判定。
 */
function stripDevLogs(source: string): string {
  return source.replace(/console\.(error|warn|info|debug)\([\s\S]*?\)\n/g, '')
}

/** 去掉媒体查询条件：断点 px 是 design-system §9 明文规定的布局分界，不属魔法值。 */
function stripMediaConditions(style: string): string {
  return style.replace(/@media[^{]*\{/g, '{')
}

function blocks(source: string, tag: 'template' | 'script' | 'style'): string {
  const pattern = new RegExp(`<${tag}[^>]*>([\\s\\S]*?)</${tag}>`, 'g')
  return [...source.matchAll(pattern)].map((match) => match[1]).join('\n')
}

function keyframeBodies(source: string): string[] {
  return [...source.matchAll(/@keyframes[^{]*\{([\s\S]*?)\n\}/g)].map((match) => match[1])
}

describe('零硬编码守护 · 文案', () => {
  it('🔴 所有 .vue 去注释后不含任何中文字面量（业务文案走 sys_config，UI 文案走 locales）', () => {
    const offenders = VUE_FILES.filter((file) => CJK.test(stripComments(read(file)))).map(rel)

    expect(offenders).toEqual([])
  })

  it('🔴 M3 源文件的中文只出现在注释与开发者日志中（UI 文案不得内联）', () => {
    const offenders = M3_FILES.filter((name) => {
      const source = read(join(SRC, name))
      const code = name.endsWith('.vue')
        ? stripComments(blocks(source, 'template') + blocks(source, 'script'))
        : stripComments(source)
      return CJK.test(stripDevLogs(code))
    })

    expect(offenders).toEqual([])
  })

  it('design-system §14.9 要求的 M3 locale key 全部存在（🔴 绝不把 key 显示给用户）', () => {
    const required = [
      'chat.toolCallTitle',
      'chat.toolCall.round',
      'chat.toolCall.expandSummary',
      'chat.toolCall.collapseSummary',
      'chat.toolCall.summaryTruncated',
      'chat.toolCall.argsLabel',
      'chat.toolCall.resultLabel',
      'chat.toolConfirm.title',
      'chat.toolConfirm.argsLabel',
      'chat.toolConfirm.allow',
      'chat.toolConfirm.deny',
      'chat.toolConfirm.announcement',
      'chat.toolConfirm.remaining',
      'chat.toolConfirm.expiring',
      'chat.toolConfirm.submitting',
      'chat.toolConfirm.stopping',
      'chat.toolConfirm.expiredSyncing',
      'chat.toolConfirm.stateSynced',
      'chat.toolConfirm.pendingBadge',
      'errors.rateLimited.title',
      'errors.rateLimited.description',
      'errors.rateLimited.recovered',
      'errors.toolDenied.description',
      'errors.toolDenied.confirmTimeout',
      'errors.toolTimeout.description',
      'errors.mcpUnavailable.description',
      'errors.toolArgsInvalid.description',
      'errors.toolLoopLimit.title',
      'errors.toolConfirmConflict.description',
      'errors.toolRetryBlocked.description',
      'errors.toolExecutionFailed.description',
      'errors.runtimeConfigInvalid.title',
      'a11y.toolCallSummaryToggle',
      'a11y.toolConfirmCard',
    ]

    const missing = required.filter((key) => t(key) === key)
    expect(missing).toEqual([])
  })

  it('8 种工具状态在 locales 中都有兜底文案且互不重复', () => {
    const statuses = [
      'pending',
      'awaiting_confirmation',
      'running',
      'succeeded',
      'failed',
      'timed_out',
      'cancelled',
      'denied',
    ]
    const labels = statuses.map((status) => t(`chat.toolStatus.${status}`))

    labels.forEach((label, index) => expect(label).not.toBe(`chat.toolStatus.${statuses[index]}`))
    expect(new Set(labels).size).toBe(statuses.length)
  })
})

describe('零硬编码守护 · 阈值只来自 sys_config', () => {
  it('🔴 tool.confirmWaitSeconds 兜底必须为 0（未下发即不渲染倒计时，绝不用 120 兜底）', () => {
    const source = read(join(SRC, 'stores/toolConfirm.ts'))
    const fallbacks = [...source.matchAll(/'confirmWaitSeconds'\s*,\s*([^)]*)\)/g)].map((m) =>
      m[1].trim(),
    )

    expect(fallbacks).toEqual(['0'])
  })

  it('🔴 埋点批量上限与事件白名单兜底为空（不内置业务默认值）', () => {
    const source = read(join(SRC, 'utils/analytics.ts'))

    expect(source).toContain("config.num('observability', 'analyticsBatchMax', 0)")
    expect(source).toContain(
      "config.json<readonly string[]>('observability', 'analyticsAllowedEvents', [])",
    )
  })

  it('🔴 状态 / 风险文案只从 display.toolStatusLabels / toolRiskLabels 读取', () => {
    const timeline = read(join(SRC, 'components/chat/ToolCallTimeline.vue'))

    expect(timeline).toContain("configStore.raw('display', 'toolStatusLabels')")
    expect(timeline).toContain("configStore.raw('display', 'toolRiskLabels')")
  })

  it('🔴 限流等待秒数只来自服务端 retryAfterSeconds，源码中无兜底秒数', () => {
    const store = read(join(SRC, 'stores/rateLimit.ts'))

    expect(store).toContain('readRetryAfterSeconds')
    expect(store).not.toMatch(/start\(\s*\d+\s*\)/)
    expect(read(join(SRC, 'composables/useCountdown.ts'))).not.toMatch(/=\s*\d{2,}\s*$/m)
  })

  it('🔴 额度数值只来自服务端快照：额度相关源码中无 3 / 50 兜底（AC-QUOTA-014）', () => {
    const quotaSources = [
      'stores/quota.ts',
      'api/quota.ts',
      'utils/quotaSnapshot.ts',
      'utils/quotaTime.ts',
      'components/chat/QuotaStatusRail.vue',
      'types/quota.ts',
    ]

    quotaSources.forEach((name) => {
      const source = stripComments(read(join(SRC, name)))
      // 🔴 不得出现平台默认阈值字面量，也不得出现"日额度 = 固定 86400 秒"这类推算
      expect(source).not.toMatch(/\b(86400|86_400)\b/)
      expect(source).not.toMatch(/limit\s*[=:]\s*\d+/)
      expect(source).not.toMatch(/remaining\s*[=:]\s*\d+/)
    })
  })

  it('🔴 日额度不得复用 QPM 通道：额度源码中无 retryAfterSeconds / 倒计时', () => {
    const quotaSources = [
      'stores/quota.ts',
      'utils/quotaTime.ts',
      'components/chat/QuotaStatusRail.vue',
      'types/quota.ts',
    ]

    quotaSources.forEach((name) => {
      const source = stripComments(read(join(SRC, name)))
      expect(source).not.toContain('readRetryAfterSeconds')
      expect(source).not.toContain('useCountdown')
    })
  })

  it('🔴 resetsAt 必须按响应 timezone 渲染（禁止无 timeZone 的 Intl 调用）', () => {
    const source = read(join(SRC, 'utils/quotaTime.ts'))
    const formatters = [...source.matchAll(/new Intl\.DateTimeFormat\(([\s\S]*?)\)\s*\./g)].map(
      (match) => match[1],
    )

    expect(formatters.length).toBeGreaterThan(0)
    formatters.forEach((args) => expect(args).toContain('timeZone'))
    // 🔴 时区非法时不得回落浏览器时区（只返回 null，由展示层省略该段）
    expect(source).not.toContain('Intl.DateTimeFormat()')
  })

  it('🔴 前端不消费摘要截断阈值、不自行截断摘要（只服从后端 truncated）', () => {
    const offenders = [...VUE_FILES, ...TS_FILES].filter((file) => {
      const source = read(file)
      return (
        /argsSummaryMaxChars|resultSummaryMaxChars/.test(source) ||
        /(argsSummary|resultSummary|summary)\s*\.\s*(slice|substring|substr)\(/.test(source)
      )
    })

    expect(offenders.map(rel)).toEqual([])
  })

  it('M3 源文件中不出现裸色值 / 裸 px / 裸动效毫秒数（全部走 Token）', () => {
    const offenders: string[] = []
    M3_FILES.filter((name) => name.endsWith('.vue')).forEach((name) => {
      const style = stripMediaConditions(stripComments(blocks(read(join(SRC, name)), 'style')))
      if (/#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/.test(style)) {
        offenders.push(`${name}:color`)
      }
      if (/[^-a-zA-Z(0-9]\d+(\.\d+)?(ms|s)\b/.test(style)) {
        offenders.push(`${name}:duration`)
      }
      // 允许 0 / 100% / 1px 描边等中性值；只拦 ≥2 位的裸 px 魔法值（断点已剔除）
      if (/[^-a-zA-Z(0-9]\d{2,}px\b/.test(style)) {
        offenders.push(`${name}:px`)
      }
    })

    expect(offenders).toEqual([])
  })
})

describe('渲染与动效纪律守护', () => {
  it('🔴 模板中使用的组件必须已 import（D-007 类静默不渲染）', () => {
    const offenders: string[] = []

    VUE_FILES.forEach((file) => {
      const source = read(file)
      const template = stripComments(blocks(source, 'template'))
      const script = blocks(source, 'script')
      const tags = new Set([...template.matchAll(/<([A-Z][A-Za-z0-9]*)/g)].map((m) => m[1]))
      tags.forEach((tag) => {
        if (VUE_BUILTIN.has(tag)) {
          return
        }
        const referenced = new RegExp(`(^|[^A-Za-z0-9])${tag}([^A-Za-z0-9]|$)`, 'm')
        if (!referenced.test(script)) {
          offenders.push(`${rel(file)} → <${tag}>`)
        }
      })
    })

    expect(offenders).toEqual([])
  })

  it('🔴 全项目禁止 transition: all', () => {
    const offenders = [...VUE_FILES, ...TS_FILES]
      .filter((file) => /transition:\s*all/.test(stripComments(read(file))))
      .map(rel)

    expect(offenders).toEqual([])
  })

  it('🔴 禁止过渡 / 动画布局属性（height / width / top / left / margin / padding）', () => {
    const offenders: string[] = []

    VUE_FILES.forEach((file) => {
      const style = stripComments(blocks(read(file), 'style'))
      if (/transition:[^;]*\b(height|width|top|left|margin|padding)\b/.test(style)) {
        offenders.push(`${rel(file)}:transition`)
      }
      keyframeBodies(style).forEach((body) => {
        if (/\b(height|width|top|left|margin|padding)\s*:/.test(body)) {
          offenders.push(`${rel(file)}:keyframes`)
        }
      })
    })

    expect(offenders).toEqual([])
  })

  it('🔴 倒计时进度只用 transform: scaleX，不动画 width', () => {
    const card = read(join(SRC, 'components/chat/ToolConfirmCard.vue'))
    const countdown = read(join(SRC, 'components/chat/ToolConfirmCountdown.vue'))

    expect(card + countdown).toContain('scaleX')
    expect(stripComments(blocks(card, 'style'))).not.toMatch(/transition:[^;]*width/)
    expect(stripComments(blocks(countdown, 'style'))).not.toMatch(/transition:[^;]*width/)
  })

  it('所有 M3 组件的位移 / 淡入动画都提供 prefers-reduced-motion 降级', () => {
    const animated = M3_FILES.filter((name) => name.endsWith('.vue')).filter((name) => {
      const style = stripComments(blocks(read(join(SRC, name)), 'style'))
      // 纯颜色过渡不属"动效"，不要求降级；animation 与 transform/opacity 过渡必须降级
      return /animation:|transition:[^;]*\b(transform|opacity)\b/.test(style)
    })
    const missing = animated.filter(
      (name) => !read(join(SRC, name)).includes('prefers-reduced-motion'),
    )

    expect(missing).toEqual([])
  })

  it('🔴 禁止用 div 模拟 button / input（真实语义标签）', () => {
    // 事件委托容器（如 Markdown 内注入的真实 button）不算模拟按钮，
    // 判定标准是「div 自称按钮」：role="button" 或 div + @click + tabindex。
    const offenders = VUE_FILES.filter((file) => {
      const template = stripComments(blocks(read(file), 'template'))
      return (
        /<div[^>]*role="button"/.test(template) ||
        /<div[^>]*@click[^>]*tabindex|<div[^>]*tabindex[^>]*@click/.test(template)
      )
    }).map(rel)

    expect(offenders).toEqual([])
  })
})

describe('鉴权与代码卫生守护', () => {
  it('🔴 token 读写只有 utils/request.ts 一处（不得出现第二套鉴权实现）', () => {
    const offenders = [...VUE_FILES, ...TS_FILES]
      .filter((file) => rel(file) !== 'utils/request.ts')
      .filter((file) => /localStorage\.(get|set|remove)Item\(\s*['"]authorization['"]/.test(read(file)))
      .map(rel)

    expect(offenders).toEqual([])
  })

  it('🔴 无 router.push(\'/login\')（本项目无 /login 路由）', () => {
    const offenders = [...VUE_FILES, ...TS_FILES]
      .filter((file) => /router\.(push|replace)\(\s*['"`]\/login/.test(stripComments(read(file))))
      .map(rel)

    expect(offenders).toEqual([])
  })

  it('🔴 无废弃错误码 10002 / 40001', () => {
    const offenders = [...VUE_FILES, ...TS_FILES]
      .filter((file) => /\b(10002|40001)\b/.test(stripComments(read(file))))
      .map(rel)

    expect(offenders).toEqual([])
  })

  it('🔴 无 console.log 残留（错误一律 console.error）', () => {
    const offenders = [...VUE_FILES, ...TS_FILES]
      .filter((file) => /console\.log\(/.test(read(file)))
      .map(rel)

    expect(offenders).toEqual([])
  })

  it('🔴 全项目禁止 any', () => {
    const offenders = [...VUE_FILES, ...TS_FILES]
      .filter((file) => /(:\s*any\b|<any>|as\s+any\b|any\[\])/.test(stripComments(read(file))))
      .map(rel)

    expect(offenders).toEqual([])
  })

  it('🔴 业务代码不重复判定鉴权失效（20000~20005 只在 request / streamRequest 收口）', () => {
    const allowed = ['utils/request.ts', 'utils/streamRequest.ts', 'types/api.ts']
    const offenders = [...VUE_FILES, ...TS_FILES]
      .filter((file) => !allowed.includes(rel(file)))
      .filter((file) =>
        /(?:code\s*[=!]==?\s*|includes\(\s*|\[\s*)2000[0-5]\b/.test(stripComments(read(file))),
      )
      .map(rel)

    expect(offenders).toEqual([])
  })

  it('🔴 SSO 跳转只有 redirectToSso 一个出口（不得自行拼 OAuth2 地址）', () => {
    const offenders = [...VUE_FILES, ...TS_FILES]
      .filter((file) => rel(file) !== 'utils/request.ts')
      .filter((file) => /\/OAuth2\?/.test(stripComments(read(file))))
      .map(rel)

    expect(offenders).toEqual([])
  })
})
