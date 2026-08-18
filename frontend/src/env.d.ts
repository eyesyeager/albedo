/// <reference types="vite/client" />

/**
 * 构建期环境变量类型。
 * 🔴 仅允许地址类配置；禁止放密钥、Token、业务配置。
 */
interface ImportMetaEnv {
  /** 后端 API 域名前缀；留空时生产按 albedo-{tenant} → api-albedo-{tenant} 运行时推导，本地走 Vite proxy 同源 */
  readonly VITE_API_DOMAIN: string
  /** 耶瞳用户中心地址 */
  readonly VITE_SSO_URL: string
  /** 耶瞳应用 clientId（前端发起授权用） */
  readonly VITE_SSO_CLIENT_ID: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
