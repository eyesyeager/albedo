/**
 * 剪贴板复制（代码块 / 回答复制）。
 *
 * 纪律：复制只读取文本，🔴 永不执行代码（AC-CHAT-005）；
 * 复制失败时给出可理解提示，不静默失败。
 */
import { ref, type Ref } from 'vue'

import { COPY_FEEDBACK_MS } from '@/utils/uiConstants'

export interface Clipboard {
  copied: Ref<boolean>
  copy: (text: string) => Promise<boolean>
}

export function useClipboard(): Clipboard {
  const copied = ref(false)
  let timer: ReturnType<typeof setTimeout> | null = null

  async function copy(text: string): Promise<boolean> {
    const ok = await writeText(text)
    if (!ok) {
      return false
    }
    copied.value = true
    if (timer !== null) {
      clearTimeout(timer)
    }
    timer = setTimeout(() => {
      copied.value = false
      timer = null
    }, COPY_FEEDBACK_MS)
    return true
  }

  return { copied, copy }
}

/** 写入剪贴板：优先标准 API，非安全上下文（http）下降级为隐藏 textarea。 */
export async function writeText(text: string): Promise<boolean> {
  if (navigator.clipboard !== undefined && window.isSecureContext) {
    const failed = await navigator.clipboard.writeText(text).then(
      () => false,
      (e: unknown) => {
        console.error('[clipboard] 写入失败', e)
        return true
      },
    )
    if (!failed) {
      return true
    }
  }
  return legacyCopy(text)
}

function legacyCopy(text: string): boolean {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', 'readonly')
  textarea.setAttribute('aria-hidden', 'true')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  textarea.style.pointerEvents = 'none'
  document.body.appendChild(textarea)
  textarea.select()
  const ok = document.execCommand('copy')
  document.body.removeChild(textarea)
  return ok
}
