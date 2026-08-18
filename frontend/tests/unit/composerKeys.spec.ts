import { describe, expect, it, vi } from 'vitest'

import { useComposerKeys } from '@/composables/useComposerKeys'

/**
 * 输入法（IME）组词保护测试（PRD UI-007）。
 *
 * 🔴 组词期间按 Enter **绝不能**发送，否则中文/日文/韩文用户会丢失输入。
 */
function keydown(init: KeyboardEventInit): KeyboardEvent {
  return new KeyboardEvent('keydown', { key: 'Enter', cancelable: true, ...init })
}

describe('useComposerKeys · Enter 语义与组词保护', () => {
  it('普通 Enter 触发发送并阻止默认换行', () => {
    const onSubmit = vi.fn()
    const keys = useComposerKeys({ onSubmit })
    const event = keydown({})

    keys.onKeydown(event)

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(event.defaultPrevented).toBe(true)
  })

  it('Shift+Enter 换行：不发送、不阻止默认行为', () => {
    const onSubmit = vi.fn()
    const keys = useComposerKeys({ onSubmit })
    const event = keydown({ shiftKey: true })

    keys.onKeydown(event)

    expect(onSubmit).not.toHaveBeenCalled()
    expect(event.defaultPrevented).toBe(false)
  })

  it('compositionstart 之后的 Enter 不发送（选字确认不应误发）', () => {
    const onSubmit = vi.fn()
    const keys = useComposerKeys({ onSubmit })

    keys.onCompositionStart()
    keys.onKeydown(keydown({}))

    expect(keys.composing.value).toBe(true)
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('compositionend 之后的 Enter 恢复发送', () => {
    const onSubmit = vi.fn()
    const keys = useComposerKeys({ onSubmit })

    keys.onCompositionStart()
    keys.onKeydown(keydown({}))
    keys.onCompositionEnd()
    keys.onKeydown(keydown({}))

    expect(keys.composing.value).toBe(false)
    expect(onSubmit).toHaveBeenCalledTimes(1)
  })

  it('event.isComposing 为 true 时不发送（未收到 compositionstart 的兜底）', () => {
    const onSubmit = vi.fn()
    const keys = useComposerKeys({ onSubmit })

    keys.onKeydown(keydown({ isComposing: true }))

    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('keyCode=229（部分 Windows IME 组词态）不发送', () => {
    const onSubmit = vi.fn()
    const keys = useComposerKeys({ onSubmit })

    keys.onKeydown(keydown({ keyCode: 229 }))

    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('非 Enter 键完全不干预', () => {
    const onSubmit = vi.fn()
    const keys = useComposerKeys({ onSubmit })
    const event = new KeyboardEvent('keydown', { key: 'a', cancelable: true })

    keys.onKeydown(event)

    expect(onSubmit).not.toHaveBeenCalled()
    expect(event.defaultPrevented).toBe(false)
  })
})
