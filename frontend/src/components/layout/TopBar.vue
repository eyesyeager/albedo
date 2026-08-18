<template>
  <header class="topbar">
    <AppButton
      v-if="!isDesktop"
      variant="ghost"
      icon-only
      :aria-label="t('a11y.openSidebar')"
      @click="emit('openSidebar')"
    >
      <template #icon><Menu :size="18" aria-hidden="true" /></template>
    </AppButton>

    <AgentSelector class="topbar-agent" />

    <h1 v-if="title.length > 0" class="topbar-title">{{ title }}</h1>

    <div class="topbar-spacer" />

    <AppButton
      v-if="!userStore.authenticated"
      class="topbar-login"
      variant="primary"
      @click="userStore.login()"
    >
      {{ loginText }}
    </AppButton>
  </header>
</template>

<script setup lang="ts">
/**
 * 顶部栏（design-system.md §10.1 / §10.2）：
 * 移动/平板菜单按钮 + Agent 选择器 + 会话标题 + 用户入口。
 * 标题过长省略；登录入口文案来自租户配置。
 */
import { Menu } from 'lucide-vue-next'
import { computed } from 'vue'

import AgentSelector from '@/components/agent/AgentSelector.vue'
import AppButton from '@/components/common/AppButton.vue'
import { t } from '@/locales'
import { useSiteStore } from '@/stores/site'
import { useUserStore } from '@/stores/user'

interface Props {
  /** 当前会话标题（首页为空） */
  title: string
  isDesktop: boolean
}

defineProps<Props>()
const emit = defineEmits<{ openSidebar: [] }>()

const siteStore = useSiteStore()
const userStore = useUserStore()

const loginText = computed(() => {
  const configured = siteStore.config?.loginText ?? ''
  return configured.length > 0 ? configured : t('auth.login')
})
</script>

<style scoped>
.topbar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  height: var(--layout-header-height);
  padding: 0 var(--spacing-base);
  border-bottom: 1px solid var(--color-border);
  background-color: var(--color-bg-base);
}

.topbar-agent {
  flex: 0 1 var(--layout-sidebar-width);
  min-width: 0;
}

.topbar-title {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--color-text-secondary);
  font-size: var(--font-size-callout);
  font-weight: var(--font-weight-medium);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar-spacer {
  flex: 1 1 auto;
}

.topbar-login {
  flex: 0 0 auto;
}

@media (max-width: 1023px) {
  .topbar-agent {
    flex: 1 1 0;
  }

  .topbar-spacer {
    display: none;
  }
}
</style>
