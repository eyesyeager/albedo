/**
 * 输入区键盘与输入法（IME）处理。
 *
 * 🔴 硬性要求（PRD UI-007）：
 *   - Enter 发送，Shift+Enter 换行
 *   - **输入法组词期间 Enter 绝不发送**（中文/日文/韩文用户会因此丢失输入）
 *
 * 组词判定使用三重保险：
 *   ① compositionstart / compositionend 自维护状态（最可靠）
 *   ② `KeyboardEvent.isComposing`（标准属性）
 *   ③ `keyCode === 229`（部分 Windows IME 在组词时上报的兼容值）
 */
import { ref, type Ref } from 'vue'

/** IME 组词时部分浏览器上报的 keyCode。 */
const IME_PROCESS_KEY_CODE = 229

export interface ComposerKeysOptions {
  /** Enter（非组词、非 Shift）时触发 */
  onSubmit: () => void
}

export interface ComposerKeys {
  composing: Ref<boolean>
  onCompositionStart: () => void
  onCompositionEnd: () => void
  onKeydown: (event: KeyboardEvent) => void
}

export function useComposerKeys(options: ComposerKeysOptions): ComposerKeys {
  const composing = ref(false)

  function onCompositionStart(): void {
    composing.value = true
  }

  function onCompositionEnd(): void {
    composing.value = false
  }

  function onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Enter') {
      return
    }
    // Shift+Enter：换行，交给浏览器默认行为
    if (event.shiftKey) {
      return
    }
    // 输入法组词中：不拦截、不发送
    if (composing.value || event.isComposing === true || event.keyCode === IME_PROCESS_KEY_CODE) {
      return
    }
    event.preventDefault()
    options.onSubmit()
  }

  return { composing, onCompositionStart, onCompositionEnd, onKeydown }
}
