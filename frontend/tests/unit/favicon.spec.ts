import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { effectScope, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import type { SiteConfig } from '@/api/site'
import { useSiteBranding } from '@/composables/useSiteBranding'
import { useSiteStore } from '@/stores/site'

import { setupPinia } from './helpers'

/**
 * 初始 favicon 占位守护（BUG-20260816-002）。
 *
 * 🔴 本 spec 守护的纪律：
 *   1. `index.html` 必须声明初始 icon，否则浏览器按默认路径探测 `/favicon.ico` → 404
 *   2. 占位必须**中性**：不含任何租户品牌资源（gift / redbook / 上传路径）
 *   3. 🔴 占位不得破坏"运行时按 siteConfig.faviconUrl 替换"的既有逻辑 ——
 *      占位节点 id 必须与 `useSiteBranding` 的 FAVICON_ID 一致，
 *      替换后页面上**只能有一个** icon 节点（否则浏览器可能仍用占位图）
 */
const INDEX_HTML = readFileSync(resolve(__dirname, '../../index.html'), 'utf8')
const BRANDING_SRC = readFileSync(
  resolve(__dirname, '../../src/composables/useSiteBranding.ts'),
  'utf8',
)

/** 从 index.html 取出初始 icon 标签原文（后续注入 jsdom，保证测的就是线上那一份）。 */
function placeholderLinkTag(): string {
  const match = INDEX_HTML.match(/<link[^>]*rel="icon"[^>]*\/?>/s)
  return match === null ? '' : match[0]
}

function faviconIdInBranding(): string {
  const match = BRANDING_SRC.match(/FAVICON_ID\s*=\s*'([^']+)'/)
  return match === null ? '' : match[1]
}

function siteConfig(faviconUrl: string): SiteConfig {
  return {
    tenantId: 't-1',
    configVersion: 1,
    timezone: 'Asia/Shanghai',
    locale: 'zh-CN',
    siteTitle: '测试站点',
    logoUrl: '/logo.png',
    faviconUrl,
    welcomeText: '',
    inputPlaceholder: '',
    loginText: '',
    registerText: '',
    newChatText: '',
    emptySessionText: '',
    agentUnavailableText: '',
    footerDisclaimer: '',
    themePrimaryColor: '',
  }
}

describe('index.html 初始 favicon 占位', () => {
  it('🔴 声明了初始 icon（消除首屏 /favicon.ico 404），且零额外请求（data-uri）', () => {
    const tag = placeholderLinkTag()

    expect(tag).not.toBe('')
    expect(tag).toMatch(/href="data:image\/png;base64,/)
  })

  it('🔴 占位不含任何租户品牌信息（不写死 gift / redbook 资源）', () => {
    expect(placeholderLinkTag()).not.toMatch(/gift|redbook|\/uploads?\//i)
  })

  it('🔴 占位 id 与 useSiteBranding 的 FAVICON_ID 一致（否则运行时替换会新增第二个节点）', () => {
    const id = faviconIdInBranding()

    expect(id).not.toBe('')
    expect(placeholderLinkTag()).toContain(`id="${id}"`)
  })
})

describe('运行时租户 favicon 替换（占位存在时）', () => {
  let scope: ReturnType<typeof effectScope>

  beforeEach(() => {
    setupPinia()
    // 还原真实首屏形态：head 中已有 index.html 声明的中性占位
    document.head.innerHTML = placeholderLinkTag()
    scope = effectScope()
  })

  afterEach(() => {
    scope.stop()
    document.head.innerHTML = ''
  })

  it('🔴 站点配置到达后原位替换为租户 favicon，且页面只有一个 icon 节点', async () => {
    const store = useSiteStore()
    scope.run(() => useSiteBranding())

    store.config = siteConfig('https://cdn.example.com/tenant-favicon.png')
    await nextTick()

    const links = document.head.querySelectorAll<HTMLLinkElement>('link[rel="icon"]')
    expect(links).toHaveLength(1)
    expect(links[0].getAttribute('href')).toBe('https://cdn.example.com/tenant-favicon.png')
    expect(links[0].id).toBe(faviconIdInBranding())
    // 🔴 占位的 type="image/png" 必须清除：租户 favicon 可能是 ico / svg
    expect(links[0].hasAttribute('type')).toBe(false)
  })

  it('租户未配置 faviconUrl 时保留中性占位（不清空、不落 404）', async () => {
    const store = useSiteStore()
    scope.run(() => useSiteBranding())

    store.config = siteConfig('')
    await nextTick()

    const links = document.head.querySelectorAll<HTMLLinkElement>('link[rel="icon"]')
    expect(links).toHaveLength(1)
    expect(links[0].getAttribute('href')).toMatch(/^data:image\/png;base64,/)
  })

  it('配置切换（多租户 / 重新发布）时仍复用同一节点', async () => {
    const store = useSiteStore()
    scope.run(() => useSiteBranding())

    store.config = siteConfig('https://cdn.example.com/a.png')
    await nextTick()
    store.config = siteConfig('https://cdn.example.com/b.png')
    await nextTick()

    const links = document.head.querySelectorAll<HTMLLinkElement>('link[rel="icon"]')
    expect(links).toHaveLength(1)
    expect(links[0].getAttribute('href')).toBe('https://cdn.example.com/b.png')
  })
})
