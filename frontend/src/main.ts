/**
 * 应用入口。
 *
 * 🔴 启动顺序（不可调换，AC-AUTH-002 / 反硬编码 / 站点状态确定性）：
 *   ① 解析 SSO 回跳 token 并立即 history.replaceState 清除 URL
 *   ② await configStore.load()  —— 平台配置（阈值/枚举）就绪
 *   ③ await siteStore.load()    —— 租户站点配置就绪（决定站点状态视图）
 *   ④ 并行预热 Agent 与当前用户身份（不阻塞挂载，组件侧有骨架态）
 *   ⑤ mount
 */
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import { useAgentStore } from './stores/agent'
import { useConfigStore } from './stores/config'
import { useSiteStore } from './stores/site'
import { useUserStore } from './stores/user'
import { consumeTokenFromUrl } from './utils/request'

import 'element-plus/dist/index.css'
import './styles/index.css'

async function bootstrap(): Promise<void> {
  // ① SSO 回跳：token 入 localStorage 并立刻从 URL 清除（不得进入日志 / Referer / 埋点）
  consumeTokenFromUrl()

  const app = createApp(App)
  app.use(createPinia())

  // ② 平台配置（失败自动回退本地快照，不白屏）
  await useConfigStore().load()

  // ③ 租户站点配置（30010/30011/30012 → 站点状态视图）
  const siteStore = useSiteStore()
  await siteStore.load()

  // ④ 站点可用时预热：Agent 列表（匿名可读）+ 当前用户身份（仅在本地有 token 时才发请求）
  if (siteStore.ready) {
    void useAgentStore().load()
    void useUserStore().load()
  }

  // 必须在 consumeTokenFromUrl() 清理地址栏之后创建 history；静态 import 会提前捕获含 token 的 URL。
  const { default: router } = await import('./router')
  app.use(router)
  await router.isReady()
  app.mount('#app')
}

void bootstrap()
