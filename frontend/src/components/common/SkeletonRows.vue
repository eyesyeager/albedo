<template>
  <div class="skeleton-rows" role="status" :aria-label="t('common.loading')">
    <span v-for="row in rows" :key="row" class="skeleton-row" />
  </div>
</template>

<script setup lang="ts">
/**
 * 骨架屏（design-system.md §6.10）：与最终结构同尺寸、保留空间防跳动；
 * Reduced Motion 下由全局规则关闭微光循环。
 */
import { t } from '@/locales'

interface Props {
  rows?: number
}

withDefaults(defineProps<Props>(), { rows: 3 })
</script>

<style scoped>
.skeleton-rows {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) 0;
}

.skeleton-row {
  position: relative;
  height: var(--control-touch-size);
  border-radius: var(--radius-md);
  background-color: var(--color-fill-subtle);
  overflow: hidden;
}

.skeleton-row::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    transparent,
    var(--color-fill-default),
    transparent
  );
  transform: translateX(-100%);
  animation: skeleton-shimmer 1600ms linear infinite;
}

@keyframes skeleton-shimmer {
  to {
    transform: translateX(100%);
  }
}
</style>
