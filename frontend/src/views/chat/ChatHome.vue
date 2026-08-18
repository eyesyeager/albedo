<template>
  <AppShell
    :title="headerTitle"
    :active-conversation-id="chatStore.conversationId"
    @new-chat="startNewChat"
    @conversation-removed="handleConversationRemoved"
  >
    <!-- 空会话主舞台：租户欢迎语 + 大号输入框（design-system §10.1） -->
    <section v-if="isEmptyStage" class="stage" :aria-label="welcomeText">
      <div class="stage-inner">
        <h2 class="stage-welcome">{{ welcomeText }}</h2>
        <p v-if="agentDescription.length > 0" class="stage-agent">{{ agentDescription }}</p>
        <ChatComposer
          ref="composer"
          class="stage-composer"
          :placeholder="placeholder"
          :tenant-id="tenantId"
          :max-chars="chatStore.messageMaxChars"
          :min-chars="chatStore.messageMinChars"
          :generating="chatStore.generating"
          :disabled="composerDisabled"
          :disabled-reason="composerDisabledReason"
          :rate-limit-remaining="rateLimitStore.remainingSeconds"
          :quota="quotaStore.view"
          :quota-exhausted="quotaStore.exhausted"
          autofocus
          @submit="handleSubmit"
          @stop="chatStore.stop()"
          @quota-retry="quotaStore.load()"
        />
        <FooterDisclaimer :text="footerText" />
      </div>
    </section>

    <!-- 会话详情：消息流 + 底部常驻输入区 -->
    <template v-else>
      <MessageList
        :messages="chatStore.messages"
        :loading="chatStore.historyLoading"
        :error="chatStore.historyError"
        :generating="chatStore.generating"
        :read-only="chatStore.readOnly"
        :rate-limit-remaining="rateLimitRemaining"
        @retry="chatStore.retry($event)"
        @reload="reloadConversation"
      />
      <div class="dock">
        <ChatComposer
          ref="composer"
          :placeholder="placeholder"
          :tenant-id="tenantId"
          :max-chars="chatStore.messageMaxChars"
          :min-chars="chatStore.messageMinChars"
          :generating="chatStore.generating"
          :disabled="composerDisabled"
          :disabled-reason="composerDisabledReason"
          :rate-limit-remaining="rateLimitStore.remainingSeconds"
          :quota="quotaStore.view"
          :quota-exhausted="quotaStore.exhausted"
          @submit="handleSubmit"
          @stop="chatStore.stop()"
          @quota-retry="quotaStore.load()"
        />
        <FooterDisclaimer :text="footerText" />
      </div>
    </template>
  </AppShell>
</template>

<script setup lang="ts">
/**
 * 对话页：同时承载首页 `/` 与会话详情 `/c/:conversationId`。
 *
 * 关键链路：
 *   1. 未登录发送 → 先存草稿再整页跳 SSO（AC-CON-001），🔴 不跳站内路由
 *   2. 首页发送 → 后端以 `conversationId=new` 原子建会话，收到 meta 后 replace 到 /c/:id
 *   3. 会话删除 / 站点异常 → 有确定视图，🔴 不白屏
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import ChatComposer from '@/components/chat/ChatComposer.vue'
import FooterDisclaimer from '@/components/chat/FooterDisclaimer.vue'
import MessageList from '@/components/chat/MessageList.vue'
import AppShell from '@/components/layout/AppShell.vue'
import { t } from '@/locales'
import { useAgentStore } from '@/stores/agent'
import { useChatStore } from '@/stores/chat'
import { useConversationStore } from '@/stores/conversation'
import { useQuotaStore } from '@/stores/quota'
import { useRateLimitStore } from '@/stores/rateLimit'
import { useSiteStore } from '@/stores/site'
import { useUserStore } from '@/stores/user'
import { saveDraft } from '@/utils/draft'

const route = useRoute()
const router = useRouter()
const siteStore = useSiteStore()
const agentStore = useAgentStore()
const chatStore = useChatStore()
const conversationStore = useConversationStore()
const quotaStore = useQuotaStore()
const rateLimitStore = useRateLimitStore()
const userStore = useUserStore()

const composer = ref<InstanceType<typeof ChatComposer> | null>(null)

const tenantId = computed(() => siteStore.config?.tenantId ?? '')
const welcomeText = computed(() => siteStore.config?.welcomeText ?? '')
const placeholder = computed(() => siteStore.config?.inputPlaceholder ?? '')
const footerText = computed(() => siteStore.config?.footerDisclaimer ?? '')
const agentDescription = computed(() => agentStore.selected?.description ?? '')

const isEmptyStage = computed(
  () => chatStore.conversationId === null && chatStore.messages.length === 0,
)

const headerTitle = computed(() => (isEmptyStage.value ? '' : chatStore.conversation?.title ?? ''))

/** 结构性禁用：无可用 Agent（EX-010）或会话只读（30040） */
const composerDisabled = computed(() => !agentStore.available || chatStore.readOnly)

const composerDisabledReason = computed(() => {
  if (chatStore.readOnly) {
    return t('chat.readOnly')
  }
  if (!agentStore.available) {
    const configured = siteStore.config?.agentUnavailableText ?? ''
    return configured.length > 0 ? configured : t('chat.agentUnavailable')
  }
  return ''
})

/**
 * 限流剩余秒数（消息级错误块用）。
 * 🔴 0 必须归一为 null：`10005` 文案带 `{remaining}` 占位，传 0 会渲染成「请等待 0 秒」。
 * ChatComposer 侧则直接用 store 原值（它以 `> 0` 判定是否禁用发送）。
 */
const rateLimitRemaining = computed(() =>
  rateLimitStore.remainingSeconds > 0 ? rateLimitStore.remainingSeconds : null,
)

function routeConversationId(): string | null {
  const value = route.params.conversationId
  return typeof value === 'string' && value.length > 0 ? value : null
}

async function handleSubmit(content: string): Promise<void> {
  // 未登录：保存草稿（只在本租户恢复）后整页跳 SSO
  if (!userStore.authenticated) {
    saveDraft(tenantId.value, content)
    userStore.login()
    return
  }
  composer.value?.clear()
  await chatStore.send(content, agentStore.selected?.agentId ?? null)
}

function startNewChat(): void {
  if (route.name === 'chat-home') {
    chatStore.reset()
    composer.value?.focus()
    return
  }
  void router.push({ name: 'chat-home' })
}

function reloadConversation(): void {
  void chatStore.open(routeConversationId())
}

function handleConversationRemoved(conversationId: string): void {
  if (chatStore.conversationId === conversationId) {
    void router.replace({ name: 'chat-home' })
  }
}

// 路由 → store：由用户点击会话 / 直接访问 URL 触发
watch(
  () => routeConversationId(),
  (id) => {
    if (id === chatStore.conversationId) {
      return // 由本次发送内部创建，历史已在内存中，避免重复请求与闪烁
    }
    void chatStore.open(id)
  },
)

// store → 路由：首页发送后拿到真实会话 ID，用 replace 保持返回栈干净
watch(
  () => chatStore.conversationId,
  (id) => {
    if (id !== null && routeConversationId() !== id) {
      void router.replace({ name: 'chat-conversation', params: { conversationId: id } })
    }
  },
)

onMounted(() => {
  void chatStore.open(routeConversationId())
  // 额度重取路径：恢复前台 + 跨标签页（到达 resetsAt 的定时器随快照排程）
  quotaStore.activate()
  if (userStore.authenticated && !conversationStore.loaded) {
    void conversationStore.loadFirstPage()
  }
})

onBeforeUnmount(() => {
  quotaStore.deactivate()
})

/**
 * 额度主体绑定（🔴 跨租户 / 跨用户隔离）。
 *
 * 🔴 匿名（uid 为 null）时 `bindSubject` 不发任何请求，也不挂载状态轨；
 * 🔴 主体变化时先清空旧快照再加载，绝不在新主体数据返回前显示上一个租户的数值。
 */
watch(
  () => [tenantId.value, userStore.me?.uid ?? null] as const,
  ([tenant, uid]) => {
    quotaStore.bindSubject(tenant, uid)
  },
  { immediate: true },
)

// 登录态就绪后再加载会话列表（未登录时不得请求受保护接口）
watch(
  () => userStore.authenticated,
  (authenticated) => {
    if (authenticated) {
      void conversationStore.loadFirstPage()
      return
    }
    conversationStore.reset()
    // 🔴 登出 / 鉴权失效：额度快照必须清空，不得残留上一主体的数值
    quotaStore.reset()
  },
)
</script>

<style scoped>
.stage {
  display: flex;
  flex: 1 1 auto;
  align-items: center;
  justify-content: center;
  min-height: 0;
  overflow-y: auto;
  padding: var(--spacing-lg) var(--spacing-base) calc(var(--spacing-lg) + var(--safe-area-bottom));
}

.stage-inner {
  width: 100%;
  max-width: var(--layout-composer-max-width);
  /* 空态整体略高于垂直中心，避免被输入区压迫（design-system §10.1） */
  margin-bottom: var(--spacing-2xl);
}

.stage-welcome {
  margin: 0 0 var(--spacing-lg);
  color: var(--color-text-primary);
  font-family: var(--font-family-display);
  font-size: var(--font-size-title-2);
  font-weight: var(--font-weight-semibold);
  line-height: var(--line-height-tight);
  letter-spacing: var(--letter-spacing-title);
  text-align: center;
}

.stage-agent {
  margin: calc(-1 * var(--spacing-md)) 0 var(--spacing-lg);
  color: var(--color-text-secondary);
  font-size: var(--font-size-footnote);
  text-align: center;
}

.dock {
  flex: 0 0 auto;
  padding: var(--spacing-sm) var(--spacing-base) calc(var(--spacing-md) + var(--safe-area-bottom));
  background-color: var(--color-bg-base);
}

@media (min-width: 768px) {
  .stage-welcome {
    font-size: var(--font-size-title-1);
  }

  .dock {
    padding-left: var(--spacing-lg);
    padding-right: var(--spacing-lg);
  }
}
</style>
