<template>
  <main class="placeholder">
    <h1 class="placeholder-title">{{ t('skeleton.title') }}</h1>
    <p class="placeholder-desc">{{ t('skeleton.description') }}</p>
  </main>
</template>

<script setup lang="ts">
/**
 * 租户管理后台占位（M2 由 @前端 实现：站点配置 / Agent / Skill / MCP / Tool / 成员 / 审计）。
 * 权限可见性以后端 @TenantRole 为最终准入，前端隐藏入口不能替代后端校验。
 */
import { t } from '@/locales'
</script>

<style scoped>
.placeholder {
  padding: var(--spacing-xl);
}

.placeholder-title {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-title-3);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.placeholder-desc {
  margin: 0;
  color: var(--color-text-secondary);
}
</style>
