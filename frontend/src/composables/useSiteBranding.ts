/**
 * 租户品牌应用：标题、favicon、主题色。
 *
 * 🔴 品牌信息全部来自 `GET /api/v1/site/config`，禁止任何硬编码。
 * 🔴 主题色必须通过 WCAG AA 校验才允许覆盖品牌 Token（design-system.md §3.1）；
 *    校验失败沿用默认 Token，不接受低对比颜色。
 */
import { watchEffect } from 'vue'

import { useSiteStore } from '@/stores/site'
import { resolveBrandOverride } from '@/utils/brandTheme'

/**
 * favicon 节点 id。
 *
 * 🔴 与 `index.html` 的中性占位 `<link id="albedo-favicon" rel="icon">` 严格对应：
 *    占位存在 → 此处**原位替换 href**（页面始终只有一个 icon 节点）；
 *    改动其中任一处 id 都会新增第二个 icon 节点，导致租户 favicon 替换看似"不生效"。
 *    该耦合由 tests/unit/favicon.spec.ts 守护。
 */
const FAVICON_ID = 'albedo-favicon'

export function useSiteBranding(): void {
  const siteStore = useSiteStore()

  watchEffect(() => {
    const config = siteStore.config
    if (config === null) {
      return
    }
    if (config.siteTitle.length > 0) {
      document.title = config.siteTitle
    }
    applyFavicon(config.faviconUrl)
    applyBrandColor(config.themePrimaryColor)
    document.documentElement.lang = config.locale.length > 0 ? config.locale : document.documentElement.lang
  })
}

function applyFavicon(faviconUrl: string): void {
  if (faviconUrl.length === 0) {
    return
  }
  const existing = document.getElementById(FAVICON_ID)
  const link = existing instanceof HTMLLinkElement ? existing : document.createElement('link')
  link.id = FAVICON_ID
  link.rel = 'icon'
  link.href = faviconUrl
  // 🔴 占位节点带 type="image/png"（占位本身是 PNG）；租户 favicon 可能是 ico / svg，
  //    残留的过期 MIME 提示会让浏览器按错误格式解码 → 图标不显示甚至回退探测默认路径。
  link.removeAttribute('type')
  if (existing === null) {
    document.head.appendChild(link)
  }
}

function applyBrandColor(themePrimaryColor: string): void {
  const override = resolveBrandOverride(themePrimaryColor)
  const style = document.documentElement.style
  if (override === null) {
    // 对比度不达标或格式非法：沿用 tokens.css 默认品牌色（不静默降级视觉可读性）
    style.removeProperty('--color-brand')
    style.removeProperty('--color-brand-hover')
    style.removeProperty('--color-brand-pressed')
    style.removeProperty('--color-brand-subtle')
    style.removeProperty('--color-text-on-brand')
    return
  }
  style.setProperty('--color-brand', override.brand)
  style.setProperty('--color-brand-hover', override.brandHover)
  style.setProperty('--color-brand-pressed', override.brandPressed)
  style.setProperty('--color-brand-subtle', override.brandSubtle)
  style.setProperty('--color-text-on-brand', override.textOnBrand)
}
