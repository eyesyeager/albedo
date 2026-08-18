<template>
  <div v-if="visible" class="message-error" role="status">
    <CircleAlert class="message-error-icon" :size="ICON_SIZE_INLINE" aria-hidden="true" />
    <div class="message-error-body">
      <!-- 未登记文案时不编造标题，只保留服务端可展示语义（🔴 但绝不静默无提示） -->
      <p v-if="text !== null" class="message-error-title">{{ t(text.titleKey) }}</p>
      <p class="message-error-desc">{{ description }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 消息级错误块（design-system.md §14.5）。
 *
 * 何时使用：错误无法在工具节点解释（无 `toolCallId`），或整次生成无法继续
 * （`30054` 调用轮次上限、`30060` 配置非法、建流前的 `10005`）。
 *
 * 🔴 纪律：
 *   1. 与正文同宽的内联块，不是浮层、不是 Toast（避免同一错误三处重复）
 *   2. 文案只走 locales；服务端 message 仅在无登记文案时作为兜底
 *   3. 不展示 endpoint / 堆栈 / 配置字段名 / 内部地址
 */
import { CircleAlert } from 'lucide-vue-next'
import { computed } from 'vue'

import { t } from '@/locales'
import { ERROR_CODE } from '@/types/api'
import { messageErrorText } from '@/utils/toolErrors'
import { ICON_SIZE_INLINE } from '@/utils/uiConstants'

interface Props {
  /** 数字业务码（null 表示只有服务端 message） */
  code: number | null
  /** 服务端可展示语义（兜底文案） */
  message: string
  /** 本条消息是否已有工具节点承载解释 */
  hasToolNode: boolean
  /** 限流剩余秒数（仅 10005 需要） */
  retryAfterSeconds?: number | null
}

const props = withDefaults(defineProps<Props>(), { retryAfterSeconds: null })

const text = computed(() => messageErrorText(props.code, props.hasToolNode))

/**
 * 🔴 D-009：错误码未登记文案时也必须给出解释。
 * 只要有服务端可展示语义就渲染（无标题、只有说明），
 * 绝不出现"失败了但页面上什么都没有"。
 */
const visible = computed(() => text.value !== null || props.message.length > 0)

const description = computed(() => {
  const resolved = text.value
  if (resolved === null) {
    return props.message
  }
  // 🔴 限流（10005）且无服务端秒数时，复用不带 {remaining} 占位的兜底文案，
  //    避免 vue-i18n 把占位符原样回显成「请等待 {remaining} 秒后再发送」。
  if (props.code === ERROR_CODE.RATE_LIMITED && props.retryAfterSeconds == null) {
    return t('errors.rateLimited.indefinite')
  }
  const remaining = props.retryAfterSeconds
  const rendered =
    remaining === null
      ? t(resolved.descriptionKey)
      : t(resolved.descriptionKey, { remaining })
  // 文案缺失时 t() 会回显 key，此时退回服务端可展示语义，绝不给用户看 key
  return rendered === resolved.descriptionKey ? props.message : rendered
})
</script>

<style scoped>
.message-error {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-sm);
  padding: var(--spacing-md);
  border: 1px solid var(--color-tool-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-tool-surface);
  font-size: var(--font-size-footnote);
}

.message-error-icon {
  flex: 0 0 auto;
  margin-top: var(--spacing-xs);
  color: var(--color-tool-status-warning);
}

.message-error-body {
  min-width: 0;
}

.message-error-title {
  margin: 0;
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}

.message-error-desc {
  margin: 0;
  color: var(--color-text-secondary);
  line-height: var(--line-height-base);
  overflow-wrap: anywhere;
}
</style>
