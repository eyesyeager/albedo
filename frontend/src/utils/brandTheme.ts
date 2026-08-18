/**
 * 租户主题色的 WCAG AA 守门（design-system.md §3.1 / §15.8）。
 *
 * 规则：租户 `themePrimaryColor` **只有在对比度校验通过后**才允许覆盖品牌语义组；
 * 校验失败继续使用 tokens.css 默认值，🔴 不允许前端静默接受低对比颜色。
 *
 * 🔴 §15.8 裁决（本次修正）：前景色必须与品牌色做**对比度感知配对**，
 *    而不是"只对白色验收"：
 *      - gift `#E5484D` 配白字约 3.91:1（不达标）、配深色字 `#16181C` 约 4.54:1（达标）
 *      - redbook `#2E6BE6` 配白字约 4.81:1（达标）
 *    只验白色会把一个**本可 AA 达标**的品牌色整体拒掉，租户品牌形同失效。
 * 🔴 配对同时决定 hover / pressed 的混合方向：
 *      白字 → 品牌色朝近黑加深（与白字对比度只升不降）
 *      深字 → 品牌色朝白色变亮（与深字对比度只升不降）
 *    🔴 三个态（brand / hover / pressed）都必须与所选前景色达标，否则整体拒绝（fail-closed）。
 */
import { WCAG_AA_CONTRAST } from '@/utils/uiConstants'

interface Rgb {
  r: number
  g: number
  b: number
}

/** 品牌语义组覆盖值（全部为 CSS 颜色字符串）。 */
export interface BrandOverride {
  brand: string
  brandHover: string
  brandPressed: string
  brandSubtle: string
  textOnBrand: string
}

const HEX_SHORT = /^#([0-9a-f]{3})$/i
const HEX_FULL = /^#([0-9a-f]{6})$/i
const WHITE: Rgb = { r: 255, g: 255, b: 255 }
const BLACK: Rgb = { r: 17, g: 17, b: 17 }
/**
 * 深色前景候选 = tokens.css 的 `--color-text-primary`（`#16181C`）。
 * 🔴 与 Token 同值：组件仍只消费 `--color-text-on-brand`，不写死租户色，也不引入第四个灰。
 */
const DARK_TEXT: Rgb = { r: 22, g: 24, b: 28 }
/** hover / pressed 的混合比例（与设计规范的"逐级加深/变亮"一致）。 */
const HOVER_MIX_RATIO = 0.12
const PRESSED_MIX_RATIO = 0.22
/** subtle 底色透明度。 */
const SUBTLE_ALPHA = 0.12

export function parseHexColor(value: string): Rgb | null {
  const input = value.trim()
  const short = HEX_SHORT.exec(input)
  if (short !== null) {
    const [r, g, b] = short[1].split('')
    return {
      r: Number.parseInt(`${r}${r}`, 16),
      g: Number.parseInt(`${g}${g}`, 16),
      b: Number.parseInt(`${b}${b}`, 16),
    }
  }
  const full = HEX_FULL.exec(input)
  if (full === null) {
    return null
  }
  return {
    r: Number.parseInt(full[1].slice(0, 2), 16),
    g: Number.parseInt(full[1].slice(2, 4), 16),
    b: Number.parseInt(full[1].slice(4, 6), 16),
  }
}

function channelLuminance(channel: number): number {
  const ratio = channel / 255
  return ratio <= 0.03928 ? ratio / 12.92 : Math.pow((ratio + 0.055) / 1.055, 2.4)
}

export function relativeLuminance(color: Rgb): number {
  return (
    0.2126 * channelLuminance(color.r) +
    0.7152 * channelLuminance(color.g) +
    0.0722 * channelLuminance(color.b)
  )
}

/** WCAG 2.1 相对亮度对比度。 */
export function contrastRatio(a: Rgb, b: Rgb): number {
  const la = relativeLuminance(a)
  const lb = relativeLuminance(b)
  const lighter = Math.max(la, lb)
  const darker = Math.min(la, lb)
  return (lighter + 0.05) / (darker + 0.05)
}

function mix(color: Rgb, target: Rgb, ratio: number): Rgb {
  const channel = (from: number, to: number): number => Math.round(from + (to - from) * ratio)
  return {
    r: channel(color.r, target.r),
    g: channel(color.g, target.g),
    b: channel(color.b, target.b),
  }
}

function toRgb(color: Rgb): string {
  return `rgb(${color.r}, ${color.g}, ${color.b})`
}

/**
 * 为品牌色挑选达标的按钮前景色（🔴 对比度感知配对，§15.8）。
 *
 * @returns 达标且对比度更高的前景色；白与深色都不达标时返回 null
 */
export function resolveBrandForeground(color: Rgb): Rgb | null {
  const candidates: readonly Rgb[] = [WHITE, DARK_TEXT]
  const best = candidates.reduce((winner, candidate) =>
    contrastRatio(color, candidate) > contrastRatio(color, winner) ? candidate : winner,
  )
  return contrastRatio(color, best) >= WCAG_AA_CONTRAST ? best : null
}

/**
 * 解析租户主题色：AA 通过则返回完整品牌语义组，否则返回 null（沿用默认 Token）。
 *
 * 校验口径（design-system §3.1「验证**按钮文字**至少 4.5:1」+ §15.8 配对裁决）：
 *   ① 在 `白` 与 `深色文字` 两个候选中取对比度更高者，且必须 ≥ 4.5:1
 *   ② hover / pressed 朝"提升该前景对比度"的方向混合
 *   ③ 🔴 三态全部复核达标才放行 —— 只验基色会让 hover 悄悄跌破 AA
 */
export function resolveBrandOverride(themePrimaryColor: string): BrandOverride | null {
  const color = parseHexColor(themePrimaryColor)
  if (color === null) {
    return null
  }
  const foreground = resolveBrandForeground(color)
  if (foreground === null) {
    return null
  }
  // 白字 → 加深；深字 → 变亮（🔴 方向错了会让交互态跌破 AA）
  const mixTarget = foreground === WHITE ? BLACK : WHITE
  const hover = mix(color, mixTarget, HOVER_MIX_RATIO)
  const pressed = mix(color, mixTarget, PRESSED_MIX_RATIO)
  const allPass = [color, hover, pressed].every(
    (state) => contrastRatio(state, foreground) >= WCAG_AA_CONTRAST,
  )
  if (!allPass) {
    return null
  }
  return {
    brand: toRgb(color),
    brandHover: toRgb(hover),
    brandPressed: toRgb(pressed),
    brandSubtle: `rgba(${color.r}, ${color.g}, ${color.b}, ${SUBTLE_ALPHA})`,
    textOnBrand: toRgb(foreground),
  }
}
