<template>
  <div class="state-block" :class="`state-block--${tone}`" role="status">
    <component :is="icon" class="state-block-icon" :size="iconSize" aria-hidden="true" />
    <p class="state-block-title">{{ title }}</p>
    <p v-if="description.length > 0" class="state-block-desc">{{ description }}</p>
    <AppButton v-if="actionText.length > 0" variant="secondary" @click="emit('action')">
      <template #icon><RotateCcw :size="16" aria-hidden="true" /></template>
      {{ actionText }}
    </AppButton>
  </div>
</template>

<script setup lang="ts">
/**
 * 空态 / 失败态 / 无权限态统一结构（design-system.md §6.10）：
 * 状态图标 + 可理解原因 + 可执行下一步；🔴 不只用颜色表达状态、不展示堆栈。
 */
import { Inbox, RotateCcw } from 'lucide-vue-next'
import type { Component } from 'vue'

import AppButton from './AppButton.vue'

interface Props {
  title: string
  description?: string
  /** 操作按钮文案（留空则不渲染按钮） */
  actionText?: string
  icon?: Component
  iconSize?: number
  tone?: 'neutral' | 'danger' | 'warning'
}

withDefaults(defineProps<Props>(), {
  description: '',
  actionText: '',
  icon: () => Inbox,
  iconSize: 32,
  tone: 'neutral',
})

const emit = defineEmits<{ action: [] }>()
</script>

<style scoped>
.state-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xl) var(--spacing-base);
  text-align: center;
}

.state-block-icon {
  color: var(--color-text-tertiary);
}

.state-block--danger .state-block-icon {
  color: var(--color-danger);
}

.state-block--warning .state-block-icon {
  color: var(--color-warning);
}

.state-block-title {
  margin: 0;
  color: var(--color-text-primary);
  font-size: var(--font-size-callout);
  font-weight: var(--font-weight-medium);
}

.state-block-desc {
  margin: 0;
  max-width: var(--status-panel-max-width);
  color: var(--color-text-secondary);
  font-size: var(--font-size-footnote);
}
</style>
