<template>
  <div class="sidebar">
    <div class="sidebar-head">
      <RouterLink class="sidebar-brand" :to="{ name: 'chat-home' }" :aria-label="t('a11y.brandHome')">
        <AvatarBlock :src="logoUrl" :name="siteTitle" shape="square" decorative />
        <span class="sidebar-brand-title">{{ siteTitle }}</span>
      </RouterLink>

      <AppButton
        v-if="showClose"
        variant="ghost"
        icon-only
        :aria-label="t('a11y.closeSidebar')"
        @click="emit('close')"
      >
        <template #icon><X :size="18" aria-hidden="true" /></template>
      </AppButton>
    </div>

    <div class="sidebar-new">
      <AppButton variant="secondary" block :disabled="!agentStore.available" @click="emit('newChat')">
        <template #icon><PenLine :size="16" aria-hidden="true" /></template>
        {{ newChatText }}
      </AppButton>
    </div>

    <!-- 未登录不请求会话列表（避免误触发 SSO 跳转），只给出登录引导 -->
    <ConversationList
      v-if="userStore.authenticated"
      class="sidebar-list"
      :active-id="activeConversationId"
      @removed="emit('conversationRemoved', $event)"
    />
    <div v-else class="sidebar-list sidebar-list--guest" />

    <UserPanel />
  </div>
</template>

<script setup lang="ts">
/**
 * 侧栏（design-system.md §10.1）：品牌区 → 新聊天 → 会话历史 → 个人区。
 *
 * 品牌信息（Logo / 标题 / 新聊天文案）全部来自租户已发布配置，
 * Logo 加载失败降级为标题首字符方形占位（PRD §7.2）。
 */
import { PenLine, X } from 'lucide-vue-next'
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

import AppButton from '@/components/common/AppButton.vue'
import AvatarBlock from '@/components/common/AvatarBlock.vue'
import ConversationList from '@/components/conversation/ConversationList.vue'
import UserPanel from '@/components/layout/UserPanel.vue'
import { t } from '@/locales'
import { useAgentStore } from '@/stores/agent'
import { useSiteStore } from '@/stores/site'
import { useUserStore } from '@/stores/user'

interface Props {
  activeConversationId: string | null
  /** 抽屉形态下显示关闭按钮 */
  showClose?: boolean
}

withDefaults(defineProps<Props>(), { showClose: false })
const emit = defineEmits<{
  newChat: []
  close: []
  conversationRemoved: [conversationId: string]
}>()

const siteStore = useSiteStore()
const agentStore = useAgentStore()
const userStore = useUserStore()

const siteTitle = computed(() => siteStore.config?.siteTitle ?? '')
const logoUrl = computed(() => siteStore.config?.logoUrl ?? '')
const newChatText = computed(() => {
  const configured = siteStore.config?.newChatText ?? ''
  return configured.length > 0 ? configured : t('chat.newChat')
})
</script>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background-color: var(--color-bg-subtle);
}

.sidebar-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-height: var(--layout-header-height);
  padding: 0 var(--spacing-sm) 0 var(--spacing-md);
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex: 1 1 auto;
  min-width: 0;
  min-height: var(--control-touch-size);
  padding: 0 var(--spacing-xs);
  border-radius: var(--radius-md);
  color: var(--color-text-primary);
  text-decoration: none;
}

.sidebar-brand-title {
  min-width: 0;
  overflow: hidden;
  font-family: var(--font-family-display);
  font-size: var(--font-size-callout);
  font-weight: var(--font-weight-semibold);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-new {
  padding: var(--spacing-sm) var(--spacing-md) var(--spacing-md);
}

.sidebar-list {
  flex: 1 1 auto;
  min-height: 0;
}
</style>
