/**
 * 每日对话额度 Store（契约：docs/api-spec.md §7.15、PRD REQ-QUOTA-003/004、design-system.md §15）。
 *
 * 🔴 为什么**新建**而不扩展 `stores/rateLimit.ts`（api-spec §7.15 开篇 + 核对项 K5）：
 *   1. QPM 与日额度是两个独立概念，恢复条件相差 5 个数量级
 *      （QPM = 等 N 秒自愈；日额度 = 等到租户当地零点，当日不可恢复）；
 *      PRD 明确要求"完全不同的状态与引导"，糅在一个 store 里必然出现
 *      "把 30070 表现成倒计时"或"倒计时结束误判额度恢复"两类缺陷；
 *   2. 输入源不同：QPM 唯一输入是 `10005 + retryAfterSeconds`（🔴 阈值不下发）；
 *      日额度唯一输入是服务端**权威快照**（9 键）+ 4 条重取路径；
 *   3. 生命周期不同：QPM 只有一个进程内倒计时；日额度绑定**主体**（tenantId + uid），
 *      切租户 / 切用户 / 登出必须清空，且要监听前台恢复、跨标签页、resetsAt；
 *   4. K5 要求 `30070` 时 `rateLimitStore.waiting` 恒 false —— 两者**物理隔离**是最简证明。
 *
 * 🔴 其他纪律：
 *   1. 🔴 匿名（uid === null）**不请求、不展示**：否则后端 20001 会让用户被无故弹去 SSO（K12）
 *   2. 🔴 不硬编码平台默认 3 / 50，不本地自减 `used`：数值只来自服务端快照
 *   3. 🔴 跨租户隔离：主体变化立刻清空快照，且丢弃"主体已变更"的在途响应
 *   4. 鉴权失效（20000~20005）已由 `request.ts` 统一清退，本文件不重复判定
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { quotaApi } from '@/api/quota'
import type { QuotaPhase, QuotaSnapshot, QuotaView } from '@/types/quota'
import { isUiHandledError } from '@/utils/errorLevel'
import { isQuotaExhausted, parseQuotaSnapshot, readQuotaSnapshotFromError } from '@/utils/quotaSnapshot'
import { QUOTA_RESET_RECHECK_MAX_MS, QUOTA_RESET_SLACK_MS } from '@/utils/uiConstants'

/**
 * 跨标签页校准信号键。
 *
 * 🔴 只写时间戳，**绝不写快照本身**：快照是受保护的个人运行数据，
 * 且每个标签页必须各自向服务端取权威值（localStorage 里的数值会立刻变成第二个真值来源）。
 */
const QUOTA_SYNC_KEY = 'albedo:quota:sync'

export const useQuotaStore = defineStore('quota', () => {
  /** 当前主体的权威快照（🔴 null 表示"没有可信数值"，绝不用默认值假装有） */
  const snapshot = ref<QuotaSnapshot | null>(null)
  const loading = ref(false)
  /** 最近一次查询是否失败（用于"额度暂不可用 + 重试"局部态） */
  const failed = ref(false)
  /** 主体标识 `tenantId#uid`；null = 匿名（🔴 不请求、不展示） */
  const subject = ref<string | null>(null)

  let resetTimer: ReturnType<typeof setTimeout> | null = null
  let listening = false

  /** 是否挂载额度状态轨（🔴 匿名恒 false：无组件、无占位、无骨架） */
  const visible = computed(() => subject.value !== null)

  const phase = computed<QuotaPhase>(() => {
    if (subject.value === null) {
      return 'hidden'
    }
    if (snapshot.value !== null) {
      // 刷新失败但仍持有**同一主体**的快照时保留展示（陈旧但可信主体），
      // 🔴 主体切换的清空在 bindSubject 中完成，绝不会展示上一个租户的数值。
      return 'ready'
    }
    return failed.value ? 'unavailable' : 'loading'
  })

  const view = computed<QuotaView>(() => ({ phase: phase.value, snapshot: snapshot.value }))

  /** 日额度已用尽 → 🔴 禁用发送（保留输入与草稿，不影响停止生成） */
  const exhausted = computed(() => isQuotaExhausted(snapshot.value))

  /**
   * 绑定当前主体（租户 + 登录用户）。
   *
   * 🔴 主体变化立即清空旧快照再拉取新快照：
   * "先清空后加载"是跨租户隔离的唯一正确顺序（AC-QUOTA-013）。
   *
   * @param tenantId 当前租户 ID（来自 site config）
   * @param uid 当前登录用户 ID；null / 空串表示匿名
   */
  function bindSubject(tenantId: string, uid: string | null): void {
    const next =
      uid === null || uid.length === 0 || tenantId.length === 0 ? null : `${tenantId}#${uid}`
    if (next === subject.value) {
      return
    }
    clearSnapshot()
    subject.value = next
    if (next !== null) {
      void load()
    }
  }

  /**
   * 拉取权威快照（初始进入 / 生成结算后 / 恢复前台 / 跨标签页 / 到达 resetsAt 共用同一条路径）。
   *
   * 🔴 复用单一路径是架构裁决（ADR-020 备选方案 E）：减少状态机分支。
   */
  async function load(): Promise<void> {
    const requested = subject.value
    if (requested === null) {
      return // 🔴 匿名不请求（K12 反向断言）
    }
    loading.value = true
    try {
      const parsed = parseQuotaSnapshot(await quotaApi.me())
      if (subject.value !== requested) {
        return // 主体已切换：丢弃过期响应，🔴 绝不写进新主体的视图
      }
      if (parsed === null) {
        // 形态非法（缺键）：按"暂不可用"处理，🔴 不用局部字段拼快照
        failed.value = true
        console.error('[quota] 额度快照形态非法，已按不可用处理')
        return
      }
      snapshot.value = parsed
      failed.value = false
      scheduleResetRefresh()
    } catch (e: unknown) {
      if (subject.value !== requested) {
        return
      }
      failed.value = true
      if (isUiHandledError(e)) {
        console.debug('[quota] 额度查询业务失败（已由界面呈现）', e)
      } else {
        console.error('[quota] 额度查询失败', e)
      }
    } finally {
      if (subject.value === requested) {
        loading.value = false
      }
    }
  }

  /**
   * 消费 `30070` 拒绝响应自带的快照。
   *
   * 🔴 用尽那一刻零延迟进入用尽态（不等下一次轮询）；
   * 🔴 该分支**绝不**触碰 QPM 倒计时（K5）。
   *
   * @returns 是否命中 `30070` 并成功消费快照
   */
  function applyRejection(error: unknown): boolean {
    const rejected = readQuotaSnapshotFromError(error)
    if (rejected === null) {
      return false
    }
    if (subject.value === null) {
      return false // 主体未绑定（理论不可达）：不展示无归属的数值
    }
    snapshot.value = rejected
    failed.value = false
    scheduleResetRefresh()
    return true
  }

  /**
   * 一次生成结束（SSE `done`）后重取权威快照，并通知其他标签页校准。
   *
   * 🔴 为什么不在 `done` 帧里带快照：架构裁决（ADR-020 备选 E）——
   * `done` 有 6 个写出分支，逐个拼快照会新增 6 个"漏拼即不一致"的错误点。
   */
  function refreshAfterSettlement(): void {
    if (subject.value === null) {
      return
    }
    void load()
    notifyOtherTabs()
  }

  /** 注册重取路径：恢复前台 + 跨标签页（resetsAt 定时器随快照排程）。 */
  function activate(): void {
    if (listening || typeof window === 'undefined') {
      return
    }
    listening = true
    document.addEventListener('visibilitychange', handleVisibility)
    window.addEventListener('storage', handleStorage)
  }

  function deactivate(): void {
    // 🔴 定时器无论是否注册过监听都要清掉（组件卸载后不得残留唤醒源）
    clearResetTimer()
    if (!listening) {
      return
    }
    listening = false
    document.removeEventListener('visibilitychange', handleVisibility)
    window.removeEventListener('storage', handleStorage)
  }

  /** 登出 / 会话结束：清空一切（🔴 不得残留上一个主体的额度）。 */
  function reset(): void {
    subject.value = null
    clearSnapshot()
  }

  // ===================== 内部实现 =====================

  function clearSnapshot(): void {
    snapshot.value = null
    failed.value = false
    loading.value = false
    clearResetTimer()
  }

  function handleVisibility(): void {
    // 🔴 恢复前台必须以服务端为准重新校准（可能已跨过 resetsAt，或其他设备已消耗额度）
    if (document.visibilityState === 'visible' && subject.value !== null && !loading.value) {
      void load()
    }
  }

  function handleStorage(event: StorageEvent): void {
    if (event.key === QUOTA_SYNC_KEY && subject.value !== null) {
      void load()
    }
  }

  function notifyOtherTabs(): void {
    try {
      // 同一标签页内 setItem 不会触发自身 storage 事件（HTML 规范），故不会自激循环
      localStorage.setItem(QUOTA_SYNC_KEY, String(Date.now()))
    } catch {
      // 隐私模式 / 配额满：跨标签页校准降级为"各自靠前台恢复重取"，不影响主链路
    }
  }

  /**
   * 到达 `resetsAt` 后重新查询。
   *
   * 🔴 一律以服务端给出的 `resetsAt` 反推等待时长（额度日可能是 23/24/25 小时），
   * 🔴 绝不以固定 86400 秒推算；到期先复核是否真的越过日界线再刷新。
   */
  function scheduleResetRefresh(): void {
    clearResetTimer()
    const current = snapshot.value
    if (current === null) {
      return
    }
    const dueMs = Date.parse(current.resetsAt) - Date.now()
    if (!Number.isFinite(dueMs)) {
      return
    }
    const delay = Math.min(
      Math.max(dueMs + QUOTA_RESET_SLACK_MS, QUOTA_RESET_SLACK_MS),
      QUOTA_RESET_RECHECK_MAX_MS,
    )
    resetTimer = setTimeout(() => {
      resetTimer = null
      const latest = snapshot.value
      if (latest !== null && Date.parse(latest.resetsAt) - Date.now() > 0) {
        scheduleResetRefresh() // 分段等待：尚未到达日界线，继续排程
        return
      }
      void load()
    }, delay)
  }

  function clearResetTimer(): void {
    if (resetTimer !== null) {
      clearTimeout(resetTimer)
      resetTimer = null
    }
  }

  return {
    snapshot,
    loading,
    failed,
    visible,
    phase,
    view,
    exhausted,
    bindSubject,
    load,
    applyRejection,
    refreshAfterSettlement,
    activate,
    deactivate,
    reset,
  }
})
