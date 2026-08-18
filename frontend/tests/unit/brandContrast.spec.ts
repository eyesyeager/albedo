import { describe, expect, it } from 'vitest'

import {
  contrastRatio,
  parseHexColor,
  resolveBrandForeground,
  resolveBrandOverride,
} from '@/utils/brandTheme'
import { WCAG_AA_CONTRAST } from '@/utils/uiConstants'

/**
 * 租户品牌色 × 前景色的**对比度感知配对**（design-system.md §15.8）。
 *
 * 🔴 本 spec 守护的纪律（与额度状态机无关，属独立的品牌样式工具任务）：
 *   1. 前景色在「白」与「深色文字 #16181C」之间按对比度择优，🔴 不是只验白色
 *   2. gift `#E5484D` → 深色字达标（≈4.54:1）；redbook `#2E6BE6` → 白字达标（≈4.81:1）
 *   3. hover / pressed 的混合方向必须**提升**所选前景的对比度，
 *      🔴 三态全部达标才放行（只验基色会让交互态悄悄跌破 AA）
 *   4. 两个方向都不达标的色值一律拒绝（fail-closed，沿用默认 Token）
 */
const DARK_TEXT = '#16181C'
const WHITE_TEXT = '#FFFFFF'

function rgb(value: string): { r: number; g: number; b: number } {
  const parsed = parseHexColor(value)
  if (parsed === null) {
    throw new Error(`invalid hex: ${value}`)
  }
  return parsed
}

/** `rgb(r, g, b)` → Rgb，用于对派生态复核对比度。 */
function fromCssRgb(value: string): { r: number; g: number; b: number } {
  const matched = /^rgb\((\d+), (\d+), (\d+)\)$/.exec(value)
  if (matched === null) {
    throw new Error(`unexpected css color: ${value}`)
  }
  return { r: Number(matched[1]), g: Number(matched[2]), b: Number(matched[3]) }
}

describe('brandTheme · 对比度事实基线（先证明数字，再验行为）', () => {
  it('gift 红配白字不达标、配深色字达标（§15.8 记录的两个数字）', () => {
    expect(contrastRatio(rgb('#E5484D'), rgb(WHITE_TEXT))).toBeLessThan(WCAG_AA_CONTRAST)
    expect(contrastRatio(rgb('#E5484D'), rgb(DARK_TEXT))).toBeGreaterThanOrEqual(WCAG_AA_CONTRAST)
  })

  it('redbook 蓝配白字达标', () => {
    expect(contrastRatio(rgb('#2E6BE6'), rgb(WHITE_TEXT))).toBeGreaterThanOrEqual(WCAG_AA_CONTRAST)
  })
})

describe('brandTheme · 前景配对', () => {
  it('gift → 深色字；redbook → 白色字（🔴 不再一律白字）', () => {
    expect(resolveBrandForeground(rgb('#E5484D'))).toEqual(rgb(DARK_TEXT))
    expect(resolveBrandForeground(rgb('#2E6BE6'))).toEqual(rgb(WHITE_TEXT))
  })

  it('黄色等高亮度品牌色配深色字达标，不再被整体拒绝', () => {
    expect(resolveBrandForeground(rgb('#FFD400'))).toEqual(rgb(DARK_TEXT))
    expect(resolveBrandOverride('#FFD400')?.textOnBrand).toBe('rgb(22, 24, 28)')
  })

  it('🔴 两个方向都不达标时返回 null（沿用默认 Token，不接受低对比）', () => {
    expect(resolveBrandForeground(rgb('#7A7A7A'))).toBeNull()
    expect(resolveBrandOverride('#7A7A7A')).toBeNull()
  })
})

describe('brandTheme · hover / pressed 方向与三态达标', () => {
  it('深色字品牌色朝白色变亮，hover / pressed 对比度只升不降', () => {
    const override = resolveBrandOverride('#E5484D')
    expect(override).not.toBeNull()
    const foreground = rgb(DARK_TEXT)
    const base = contrastRatio(rgb('#E5484D'), foreground)

    const hover = contrastRatio(fromCssRgb(override!.brandHover), foreground)
    const pressed = contrastRatio(fromCssRgb(override!.brandPressed), foreground)

    expect(hover).toBeGreaterThan(base)
    expect(pressed).toBeGreaterThan(hover)
  })

  it('白字品牌色朝近黑加深，hover / pressed 对比度只升不降', () => {
    const override = resolveBrandOverride('#2E6BE6')
    expect(override).not.toBeNull()
    const foreground = rgb(WHITE_TEXT)
    const base = contrastRatio(rgb('#2E6BE6'), foreground)

    const hover = contrastRatio(fromCssRgb(override!.brandHover), foreground)
    const pressed = contrastRatio(fromCssRgb(override!.brandPressed), foreground)

    expect(hover).toBeGreaterThan(base)
    expect(pressed).toBeGreaterThan(hover)
  })

  it('🔴 brand / hover / pressed 三态与所选前景全部 ≥ AA（含 gift 与 redbook）', () => {
    ;['#E5484D', '#2E6BE6', '#000', '#FFD400'].forEach((seed) => {
      const override = resolveBrandOverride(seed)
      expect(override).not.toBeNull()
      const foreground = fromCssRgb(override!.textOnBrand)
      ;[override!.brand, override!.brandHover, override!.brandPressed].forEach((state) => {
        expect(contrastRatio(fromCssRgb(state), foreground)).toBeGreaterThanOrEqual(
          WCAG_AA_CONTRAST,
        )
      })
    })
  })

  it('brandSubtle 仍是同色低透明度底（品牌语义组不出现半套主题）', () => {
    const override = resolveBrandOverride('#E5484D')

    expect(override?.brandSubtle).toContain('rgba(229, 72, 77')
  })
})
