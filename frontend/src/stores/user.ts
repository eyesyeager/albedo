/**
 * 当前用户 Store。
 *
 * 🔴 鉴权纪律：
 *   1. 未登录时**绝不**调用受保护接口（否则后端返回 20001 会立刻整页跳 SSO，造成误跳）
 *   2. 登录 / 注册 / 退出 一律走 `redirectToSso()`（整页跳转，本项目无 /login 路由）
 *   3. 退出 = 清 token + 跳 SSO，不发任何后端请求（AC-AUTH-008）
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { meApi } from '@/api/me'
import { t } from '@/locales'
import { ERROR_CODE } from '@/types/api'
import type { MeInfo } from '@/types/user'
import { clearDraft } from '@/utils/draft'
import { ApiError, isLoggedIn, redirectToSso } from '@/utils/request'

export const useUserStore = defineStore('user', () => {
  const me = ref<MeInfo | null>(null)
  const loading = ref(false)
  const loaded = ref(false)
  /** 身份类错误的可展示语义（如成员被停用 10003） */
  const errorMessage = ref<string | null>(null)
  /** 本地是否持有 token（不代表 token 一定有效） */
  const hasToken = ref(isLoggedIn())

  /** 已确认可用的登录态：既有 token，也成功取回身份 */
  const authenticated = computed(() => hasToken.value && me.value !== null)
  const displayName = computed(() => me.value?.nickname ?? '')

  /** 拉取当前用户身份（顺带触发后端惰性建户）。 */
  async function load(): Promise<void> {
    hasToken.value = isLoggedIn()
    errorMessage.value = null
    if (!hasToken.value) {
      me.value = null
      loaded.value = true
      return
    }
    loading.value = true
    try {
      me.value = await meApi.get()
    } catch (e: unknown) {
      // 20000~20005 已由 request.ts 统一清退，此处只处理业务/系统错误
      me.value = null
      errorMessage.value = describeIdentityError(e)
      console.error('[user] 身份加载失败', e)
    } finally {
      loading.value = false
      loaded.value = true
    }
  }

  /** 发起登录 / 注册（同一 SSO 入口）。 */
  function login(): void {
    redirectToSso()
  }

  /** 退出登录：清 token + 清租户本地临时状态 + 整页跳 SSO。 */
  function logout(tenantId: string): void {
    me.value = null
    hasToken.value = false
    clearDraft(tenantId)
    redirectToSso()
  }

  return { me, loading, loaded, errorMessage, hasToken, authenticated, displayName, load, login, logout }
})

function describeIdentityError(e: unknown): string {
  if (e instanceof ApiError) {
    return e.code === ERROR_CODE.PERMISSION_DENIED ? t('auth.memberDisabled') : e.message
  }
  return t('auth.identityLoadFailed')
}
