/**
 * 配置读取组合式函数（反硬编码消费入口）。
 *
 * 用法：
 * ```ts
 * const { num } = useConfig()
 * const maxChars = num('chat', 'messageMaxChars', 20000)
 * ```
 *
 * 🔴 组件内禁止直接写死业务阈值 / 枚举；🔴 fallback 仅用于配置服务不可用时的兜底渲染，
 * 不得作为"业务默认值"长期依赖（配置缺失应由 @后端 在 sys_config 补齐）。
 */
import { useConfigStore } from '@/stores/config'

export function useConfig() {
  const store = useConfigStore()

  return {
    num: (group: string, key: string, fallback: number): number => store.num(group, key, fallback),
    bool: (group: string, key: string, fallback: boolean): boolean =>
      store.bool(group, key, fallback),
    str: (group: string, key: string, fallback: string): string => store.str(group, key, fallback),
    json: <T>(group: string, key: string, fallback: T): T => store.json<T>(group, key, fallback),
    degraded: () => store.degraded,
  }
}
