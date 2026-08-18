/**
 * 路由配置。
 *
 * 🔴 红线：
 *   1. **不存在 /login 路由**：登录 / 注册 / 鉴权失效一律整页跳转耶瞳 SSO
 *   2. 需要登录的路由用 meta.requiresAuth 标记，守卫内调用 redirectToSso()
 *   3. 站点异常（30010/30011/30012）由 siteStore 状态驱动到 /error/* 视图，禁止白屏
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useSiteStore } from '@/stores/site'
import { isLoggedIn, redirectToSso } from '@/utils/request'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'chat-home',
    component: () => import('@/views/chat/ChatHome.vue'),
    meta: { requiresAuth: false },
  },
  {
    path: '/c/:conversationId',
    name: 'chat-conversation',
    component: () => import('@/views/chat/ChatHome.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/admin',
    name: 'admin',
    component: () => import('@/views/admin/AdminPlaceholder.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/admin/:pathMatch(.*)*',
    name: 'admin-children',
    component: () => import('@/views/admin/AdminPlaceholder.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/platform',
    name: 'platform',
    component: () => import('@/views/platform/PlatformPlaceholder.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/platform/:pathMatch(.*)*',
    name: 'platform-children',
    component: () => import('@/views/platform/PlatformPlaceholder.vue'),
    meta: { requiresAuth: true },
  },
  // ===== 站点级状态视图（对应 30010 / 30011 / 30012） =====
  {
    path: '/error/site-not-found',
    name: 'site-not-found',
    component: () => import('@/views/error/SiteNotFound.vue'),
    meta: { requiresAuth: false, siteAgnostic: true },
  },
  {
    path: '/error/site-suspended',
    name: 'site-suspended',
    component: () => import('@/views/error/SiteSuspended.vue'),
    meta: { requiresAuth: false, siteAgnostic: true },
  },
  {
    path: '/error/site-unavailable',
    name: 'site-unavailable',
    component: () => import('@/views/error/SiteUnavailable.vue'),
    meta: { requiresAuth: false, siteAgnostic: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    redirect: { name: 'chat-home' },
  },
]

const SITE_STATUS_ROUTE: Record<string, string> = {
  notFound: 'site-not-found',
  suspended: 'site-suspended',
  unavailable: 'site-unavailable',
  error: 'site-unavailable',
}

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  // ① 站点不可用：强制进入对应状态视图（不展示登录入口 / Agent / 业务数据）
  const siteStore = useSiteStore()
  if (siteStore.loaded && siteStore.status !== 'ready' && to.meta.siteAgnostic !== true) {
    const target = SITE_STATUS_ROUTE[siteStore.status]
    if (target !== undefined) {
      return { name: target }
    }
  }

  // ② 未登录访问受保护路由 → 整页跳 SSO（禁止 router.push('/login')）
  if (to.meta.requiresAuth === true && !isLoggedIn()) {
    redirectToSso(new URL(to.fullPath, location.origin).href)
    return false
  }

  return true
})

export default router
