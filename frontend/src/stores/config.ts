/**
 * 平台配置 Store（反硬编码主链路）。
 *
 * 纪律：
 *   1. main.ts 在挂载前 `await load()`，避免组件读到空配置产生闪烁
 *   2. 拉取失败时回退 localStorage 快照并标记降级，🔴 不得白屏、🔴 不得内置业务默认值
 *   3. 组件一律通过 num/bool/str/json 读取，禁止在组件内写死阈值与枚举
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

import { sysConfigApi, type SysConfigMap } from '@/api/sysConfig'

const SNAPSHOT_KEY = 'sys_config_snapshot'

export const useConfigStore = defineStore('config', () => {
  const config = ref<SysConfigMap>({})
  const loaded = ref(false)
  /** 是否处于「使用本地快照」的降级状态（用于给用户温和提示）。 */
  const degraded = ref(false)

  async function load(): Promise<void> {
    try {
      config.value = await sysConfigApi.all()
      degraded.value = false
      localStorage.setItem(SNAPSHOT_KEY, JSON.stringify(config.value))
    } catch {
      const snapshot = localStorage.getItem(SNAPSHOT_KEY)
      if (snapshot !== null) {
        config.value = JSON.parse(snapshot) as SysConfigMap
        degraded.value = true
      } else {
        config.value = {}
        degraded.value = true
      }
    } finally {
      loaded.value = true
    }
  }

  function raw(group: string, key: string): unknown {
    return config.value[group]?.[key]
  }

  function num(group: string, key: string, fallback: number): number {
    const value = raw(group, key)
    return typeof value === 'number' ? value : fallback
  }

  function bool(group: string, key: string, fallback: boolean): boolean {
    const value = raw(group, key)
    return typeof value === 'boolean' ? value : fallback
  }

  function str(group: string, key: string, fallback: string): string {
    const value = raw(group, key)
    return typeof value === 'string' ? value : fallback
  }

  function json<T>(group: string, key: string, fallback: T): T {
    const value = raw(group, key)
    return value === undefined || value === null ? fallback : (value as T)
  }

  return { config, loaded, degraded, load, raw, num, bool, str, json }
})
