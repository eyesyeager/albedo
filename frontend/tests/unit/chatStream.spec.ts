import { describe, expect, it, vi } from 'vitest'

import { contrastRatio, parseHexColor, resolveBrandOverride } from '@/utils/brandTheme'
import { createTextBuffer } from '@/utils/textBuffer'
import { WCAG_AA_CONTRAST } from '@/utils/uiConstants'

/**
 * 流式分片合并与租户主题色 AA 守门测试。
 */
describe('textBuffer · 分片按帧合并', () => {
  it('多个分片合并为一次提交，避免逐字 diff 造成重渲染风暴', async () => {
    const commit = vi.fn()
    const buffer = createTextBuffer(commit)

    buffer.push('你')
    buffer.push('好')
    buffer.push('世界')
    expect(commit).not.toHaveBeenCalled()

    buffer.flush()

    expect(commit).toHaveBeenCalledTimes(1)
    expect(commit).toHaveBeenCalledWith('你好世界')
  })

  it('flush 后缓冲清空，不会重复提交同一段文本', () => {
    const commit = vi.fn()
    const buffer = createTextBuffer(commit)

    buffer.push('A')
    buffer.flush()
    buffer.flush()

    expect(commit).toHaveBeenCalledTimes(1)
  })

  it('cancel 丢弃未提交内容（切换会话 / 中断生成）', () => {
    const commit = vi.fn()
    const buffer = createTextBuffer(commit)

    buffer.push('待丢弃')
    buffer.cancel()
    buffer.flush()

    expect(commit).not.toHaveBeenCalled()
  })
})

describe('brandTheme · 租户主题色 WCAG AA 守门', () => {
  it('🔴 白与深色前景都不达标的主题色被拒绝，沿用默认 Token', () => {
    // 中间亮度灰（L≈0.19）：配白约 4.33:1、配 #16181C 约 4.10:1 —— 两个方向都不达标
    expect(resolveBrandOverride('#7A7A7A')).toBeNull()
  })

  it('🔴 gift 主色改为与深色文字配对（只验白色会把可达标品牌色整体拒掉，§15.8）', () => {
    const override = resolveBrandOverride('#E5484D')

    expect(override).not.toBeNull()
    expect(override?.brand).toBe('rgb(229, 72, 77)')
    // 深色前景 = tokens.css 的 --color-text-primary
    expect(override?.textOnBrand).toBe('rgb(22, 24, 28)')
    expect(
      contrastRatio(parseHexColor('#E5484D')!, parseHexColor('#16181C')!),
    ).toBeGreaterThanOrEqual(WCAG_AA_CONTRAST)
  })

  it('对比度达标的主题色生成完整品牌语义组（白色按钮文字）', () => {
    // redbook 种子主题色 #2E6BE6 与白色文字约 4.8:1 → 达标
    const override = resolveBrandOverride('#2E6BE6')

    expect(override).not.toBeNull()
    expect(override?.brand).toBe('rgb(46, 107, 230)')
    expect(override?.brandHover.length).toBeGreaterThan(0)
    expect(override?.brandPressed.length).toBeGreaterThan(0)
    expect(override?.brandSubtle).toContain('rgba(46, 107, 230')
    expect(override?.textOnBrand).toBe('rgb(255, 255, 255)')
  })

  it('非法色值返回 null', () => {
    expect(resolveBrandOverride('')).toBeNull()
    expect(resolveBrandOverride('not-a-color')).toBeNull()
    expect(resolveBrandOverride('rgb(1,2,3)')).toBeNull()
  })

  it('支持三位缩写十六进制', () => {
    expect(resolveBrandOverride('#000')).not.toBeNull()
  })
})
