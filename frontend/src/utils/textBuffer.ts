/**
 * 流式文本分片合并缓冲。
 *
 * 为什么需要：SSE `delta` 事件可能以极高频率到达（每个 token 一帧）。
 * 若每片都直接写入响应式状态，会触发一次渲染 + Markdown 重新解析，
 * 造成重渲染风暴与掉帧。因此按 `requestAnimationFrame` 合并为**每帧一次**写入
 * （design-system.md §6.6：分片合并后提交 DOM，仅做 ≤120ms opacity 淡入）。
 */
export interface TextBuffer {
  /** 追加一个分片（不立即写入） */
  push: (text: string) => void
  /** 立即写出缓冲内容（done / error / 组件卸载前必须调用） */
  flush: () => void
  /** 丢弃缓冲并取消已排定的帧（切换会话 / 中断时使用） */
  cancel: () => void
}

/**
 * @param commit 帧回调：把合并后的文本一次性写入响应式状态
 */
export function createTextBuffer(commit: (merged: string) => void): TextBuffer {
  let pending = ''
  let frameId: number | null = null

  const hasRaf = typeof requestAnimationFrame === 'function'

  function write(): void {
    frameId = null
    if (pending.length === 0) {
      return
    }
    const merged = pending
    pending = ''
    commit(merged)
  }

  function schedule(): void {
    if (frameId !== null) {
      return
    }
    if (!hasRaf) {
      // 非浏览器环境（单测 / SSR）退化为微任务，行为等价、时序更紧凑
      frameId = 1
      void Promise.resolve().then(write)
      return
    }
    frameId = requestAnimationFrame(write)
  }

  return {
    push(text: string): void {
      if (text.length === 0) {
        return
      }
      pending += text
      schedule()
    },
    flush(): void {
      if (frameId !== null && hasRaf) {
        cancelAnimationFrame(frameId)
      }
      frameId = null
      write()
    },
    cancel(): void {
      if (frameId !== null && hasRaf) {
        cancelAnimationFrame(frameId)
      }
      frameId = null
      pending = ''
    },
  }
}
