<template>
  <div class="shell">
    <a class="shell-skip" href="#main-content">{{ t('a11y.skipToMain') }}</a>

    <!-- ≥1024：固定侧栏 -->
    <aside v-if="isDesktop" class="shell-sidebar">
      <SidebarPanel
        :active-conversation-id="activeConversationId"
        @new-chat="emit('newChat')"
        @conversation-removed="emit('conversationRemoved', $event)"
      />
    </aside>

    <!-- <1024：左侧抽屉（焦点圈定 / Esc 关闭 / 遮罩关闭由 el-drawer 保证） -->
    <ElDrawer
      v-else
      v-model="drawerOpen"
      direction="ltr"
      :with-header="false"
      :size="drawerSize"
      class="shell-drawer"
      :z-index="drawerZIndex"
    >
      <SidebarPanel
        :active-conversation-id="activeConversationId"
        show-close
        @new-chat="handleDrawerNewChat"
        @close="drawerOpen = false"
        @conversation-removed="emit('conversationRemoved', $event)"
      />
    </ElDrawer>

    <div class="shell-main">
      <TopBar :title="title" :is-desktop="isDesktop" @open-sidebar="drawerOpen = true" />
      <main id="main-content" class="shell-content">
        <slot />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 应用外壳（design-system.md §9 四档响应式）。
 *
 * ≥1024px 固定 280px 侧栏；<1024px 侧栏转为左侧抽屉。
 * 断点判断只用 matchMedia，不做 JS 宽度测量（避免 resize 抖动）。
 */
import { ElDrawer } from 'element-plus'
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import SidebarPanel from '@/components/layout/SidebarPanel.vue'
import TopBar from '@/components/layout/TopBar.vue'
import { useMediaQuery } from '@/composables/useMediaQuery'
import { t } from '@/locales'
import { readCssNumberToken } from '@/utils/cssToken'
import { DESKTOP_MEDIA_QUERY } from '@/utils/uiConstants'

interface Props {
  title: string
  activeConversationId: string | null
}

defineProps<Props>()
const emit = defineEmits<{ newChat: []; conversationRemoved: [conversationId: string] }>()

const route = useRoute()
const isDesktop = useMediaQuery(DESKTOP_MEDIA_QUERY)
const drawerOpen = ref(false)

/** 抽屉宽度直接用 Token；层级从 tokens.css 实时读取（组件库只接受 number） */
const drawerSize = 'var(--drawer-width)'
const drawerZIndex = ref<number | undefined>(undefined)

onMounted(() => {
  drawerZIndex.value = readCssNumberToken('--z-drawer')
})

function handleDrawerNewChat(): void {
  drawerOpen.value = false
  emit('newChat')
}

// 路由变化（含点击会话）后自动收起抽屉，避免遮挡内容
watch(
  () => route.fullPath,
  () => {
    drawerOpen.value = false
  },
)

watch(isDesktop, (desktop) => {
  if (desktop) {
    drawerOpen.value = false
  }
})
</script>

<style scoped>
.shell {
  display: flex;
  height: 100%;
  min-height: 0;
  background-color: var(--color-bg-base);
}

.shell-skip {
  position: absolute;
  top: var(--spacing-sm);
  left: var(--spacing-sm);
  z-index: var(--z-toast);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-elevated);
  color: var(--color-text-primary);
  font-size: var(--font-size-footnote);
  transform: translateY(calc(-1 * var(--spacing-3xl) * 2));
  transition: transform var(--duration-fast) var(--ease-enter);
}

.shell-skip:focus-visible {
  transform: translateY(0);
}

.shell-sidebar {
  flex: 0 0 var(--layout-sidebar-width);
  width: var(--layout-sidebar-width);
  border-right: 1px solid var(--color-border);
}

.shell-main {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
}

.shell-content {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
}
</style>

<!-- 抽屉浮层被 teleport 到 body：内容内边距需在全局作用域归零 -->
<style>
.shell-drawer .el-drawer__body {
  padding: 0;
  overflow: hidden;
}
</style>
