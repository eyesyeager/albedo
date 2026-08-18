<template>
  <div class="user-panel">
    <!-- 未登录：登录引导（文案来自租户配置，🔴 不硬编码） -->
    <template v-if="!userStore.authenticated">
      <p class="user-panel-hint">{{ t('auth.loginRequired') }}</p>
      <p v-if="userStore.errorMessage !== null" class="user-panel-error">
        {{ userStore.errorMessage }}
      </p>
      <div class="user-panel-auth">
        <AppButton variant="primary" block @click="userStore.login()">{{ loginText }}</AppButton>
        <AppButton variant="ghost" block @click="userStore.login()">{{ registerText }}</AppButton>
      </div>
    </template>

    <!-- 已登录：头像 + 昵称 + 菜单（🔴 不展示任何计费入口） -->
    <ElDropdown v-else trigger="click" placement="top-start" @command="handleCommand">
      <button class="user-panel-trigger" type="button" :aria-label="t('a11y.userMenu')">
        <AvatarBlock :src="avatarUrl" :name="nickname" shape="circle" decorative />
        <span class="user-panel-name">{{ nickname }}</span>
        <ChevronUp :size="16" aria-hidden="true" />
      </button>
      <template #dropdown>
        <ElDropdownMenu>
          <ElDropdownItem command="userCenter">{{ t('auth.profile') }}</ElDropdownItem>
          <ElDropdownItem command="webmaster">{{ t('auth.webmaster') }}</ElDropdownItem>
          <ElDropdownItem command="logout" divided>{{ t('auth.logout') }}</ElDropdownItem>
        </ElDropdownMenu>
      </template>
    </ElDropdown>
  </div>
</template>

<script setup lang="ts">
/**
 * 侧栏底部个人区（PRD §7.2 / UI-005）。
 *
 * 未登录 → 登录 / 注册引导（均整页跳同一 SSO 地址）；
 * 已登录 → 头像 + 昵称 + 菜单（用户信息 / 站长信息 / 退出登录）。
 * 🔴 用户信息 / 站长信息 → 新开页面跳耶瞳平台（user / space），不发后端请求。
 * 🔴 退出 = 清 token + 整页跳 SSO，不发后端请求（AC-AUTH-008）。
 * 🔴 M1 不展示计费入口，也不展示尚未实现的管理入口（不做假入口）。
 */
import { ElDropdown, ElDropdownItem, ElDropdownMenu } from 'element-plus'
import { ChevronUp } from 'lucide-vue-next'
import { computed } from 'vue'

import AppButton from '@/components/common/AppButton.vue'
import AvatarBlock from '@/components/common/AvatarBlock.vue'
import { t } from '@/locales'
import { useSiteStore } from '@/stores/site'
import { useUserStore } from '@/stores/user'

/** 耶瞳用户中心（账号信息），新开页跳转 */
const USER_CENTER_URL = 'https://user.eyescode.top'
/** 耶瞳站长空间（站长信息），新开页跳转 */
const WEBMASTER_URL = 'https://space.eyescode.top'

const userStore = useUserStore()
const siteStore = useSiteStore()

const loginText = computed(() => fallback(siteStore.config?.loginText, t('auth.login')))
const registerText = computed(() => fallback(siteStore.config?.registerText, t('auth.register')))
const nickname = computed(() => fallback(userStore.me?.nickname, t('auth.profile')))
const avatarUrl = computed(() => userStore.me?.avatarUrl ?? '')

/**
 * 新开页面跳转外部平台，强制 noopener/noreferrer 防止 window.opener 反向控制。
 */
function openExternal(url: string): void {
  window.open(url, '_blank', 'noopener,noreferrer')
}

function handleCommand(command: string | number | object): void {
  if (command === 'userCenter') {
    openExternal(USER_CENTER_URL)
    return
  }
  if (command === 'webmaster') {
    openExternal(WEBMASTER_URL)
    return
  }
  if (command === 'logout') {
    userStore.logout(siteStore.config?.tenantId ?? '')
  }
}

function fallback(value: string | undefined, defaultValue: string): string {
  return value !== undefined && value.length > 0 ? value : defaultValue
}
</script>

<style scoped>
.user-panel {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  border-top: 1px solid var(--color-border);
}

.user-panel-hint {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-footnote);
  line-height: var(--line-height-base);
}

.user-panel-error {
  display: flex;
  margin: 0;
  color: var(--color-danger);
  font-size: var(--font-size-footnote);
}

.user-panel-auth {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.user-panel-trigger {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  width: 100%;
  min-height: var(--control-touch-size);
  padding: 0 var(--spacing-sm);
  border: none;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-primary);
  font-family: var(--font-family-body);
  font-size: var(--font-size-callout);
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-enter);
}

@media (hover: hover) and (pointer: fine) {
  .user-panel-trigger:hover {
    background-color: var(--color-fill-subtle);
  }
}

.user-panel-name {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
