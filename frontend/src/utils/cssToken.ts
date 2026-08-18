/**
 * 读取 CSS 设计 Token 的运行时值。
 *
 * 用途：少数第三方组件（如 Element Plus 的 `z-index` 属性）只接受 **number**，
 * 无法直接消费 `var(--z-drawer)`。此时从 `tokens.css` 实时读取，
 * 保证 Token 仍是唯一来源，🔴 避免在 TS 中出现平行的层级/尺寸字面量。
 */

/** 读取数值型 Token；读不到时返回 undefined（交由组件库使用自身默认值）。 */
export function readCssNumberToken(name: string): number | undefined {
  if (typeof getComputedStyle !== 'function') {
    return undefined
  }
  const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  if (raw.length === 0) {
    return undefined
  }
  const value = Number.parseFloat(raw)
  return Number.isFinite(value) ? value : undefined
}
