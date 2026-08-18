<template>
  <main class="status-page">
    <section class="status-panel" role="alert" aria-live="polite">
      <component :is="icon" class="status-icon" :class="`status-icon--${tone}`" :size="40" aria-hidden="true" />
      <h1 class="status-title">{{ title }}</h1>
      <p class="status-hint">{{ hint }}</p>
      <AppButton v-if="retryText.length > 0" variant="secondary" @click="emit('retry')">
        <template #icon><RotateCcw :size="16" aria-hidden="true" /></template>
        {{ retryText }}
      </AppButton>
    </section>
  </main>
</template>

<script setup lang="ts">
/**
 * 站点级状态面板（design-system.md §6.12）：404 / 403 / 503 共用同一模板。
 *
 * 🔴 不展示 Logo、Agent、登录入口或任何租户业务数据（PRD §5.1）；
 * 🔴 状态由图标 + 文案共同表达，不只靠颜色。
 * 文案来自 locales（静态 UI 文案），禁止内联字面量。
 */
import { HelpCircle, RotateCcw } from 'lucide-vue-next'
import type { Component } from 'vue'

import AppButton from '@/components/common/AppButton.vue'

interface Props {
  /** 状态主标题 */
  title: string
  /** 状态说明（不得泄露租户或内部配置细节） */
  hint: string
  icon?: Component
  tone?: 'neutral' | 'warning' | 'danger'
  /** 重试按钮文案（留空则不提供重试） */
  retryText?: string
}

withDefaults(defineProps<Props>(), {
  icon: () => HelpCircle,
  tone: 'neutral',
  retryText: '',
})

const emit = defineEmits<{ retry: [] }>()
</script>

<style scoped>
.status-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: var(--spacing-lg);
  background-color: var(--color-bg-base);
}

.status-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  max-width: var(--status-panel-max-width);
  text-align: center;
}

.status-icon--neutral {
  color: var(--color-text-tertiary);
}

.status-icon--warning {
  color: var(--color-warning);
}

.status-icon--danger {
  color: var(--color-danger);
}

.status-title {
  margin: var(--spacing-sm) 0 0;
  font-family: var(--font-family-display);
  font-size: var(--font-size-title-2);
  font-weight: var(--font-weight-semibold);
  line-height: var(--line-height-tight);
  letter-spacing: var(--letter-spacing-title);
  color: var(--color-text-primary);
}

.status-hint {
  margin: 0 0 var(--spacing-sm);
  color: var(--color-text-secondary);
  font-size: var(--font-size-callout);
}
</style>
