<template>
  <main class="placeholder">
    <h1 class="placeholder-title">{{ t('skeleton.title') }}</h1>
    <p class="placeholder-desc">{{ t('skeleton.description') }}</p>
  </main>
</template>

<script setup lang="ts">
/**
 * 平台管理后台占位（M2 由 @前端 实现：租户管理 / 域名管理 / 本地 Tool 注册表 / 受控跨租户授权）。
 * 平台管理员身份仅由 eyesUser role=ADMIN 认定，不自动获得任何租户内角色。
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
