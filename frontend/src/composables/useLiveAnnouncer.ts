/**
 * 全局唯一的礼貌播报通道（design-system.md §14.3.3 / §14.8）。
 *
 * 存在理由：消息流里已经有**一个** `aria-live="polite"` 区域。工具确认卡、状态同步、
 * 限流恢复等语义如果各自新建 live region，读屏会被并发播报轰炸。
 *
 * 🔴 纪律：
 *   1. 只有一个 live region（`MessageList`），本模块只是它的数据源
 *   2. 同一 key 只播报一次（倒计时逐秒变化、心跳、running 分片一律不得进入）
 *   3. 内容为 locale 文案，绝不播报原始错误码 / 摘要正文
 */
import { readonly, ref } from 'vue'

const message = ref('')
const announced = new Set<string>()

/** 供 live region 绑定的只读文本。 */
export const liveAnnouncement = readonly(message)

/**
 * 播报一次。
 *
 * @param key 去重键（如 `confirm:9001`）；同一 key 重复调用被忽略
 * @param text 播报文案（必须来自 locales）
 */
export function announceOnce(key: string, text: string): void {
  if (announced.has(key) || text.length === 0) {
    return
  }
  announced.add(key)
  message.value = text
}

/** 会话切换 / 新一轮生成时清空，避免跨会话的陈旧播报与去重键堆积。 */
export function resetAnnouncements(): void {
  announced.clear()
  message.value = ''
}
