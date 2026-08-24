import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ChatComposer from '@/components/chat/ChatComposer.vue'
import QuotaStatusRail from '@/components/chat/QuotaStatusRail.vue'
import { t } from '@/locales'
import zhCN from '@/locales/zh-CN'
import { useQuotaStore } from '@/stores/quota'
import { useRateLimitStore } from '@/stores/rateLimit'
import { ERROR_CODE } from '@/types/api'
import type { QuotaSnapshot, QuotaView } from '@/types/quota'
import {
  isQuotaExhausted,
  parseQuotaSnapshot,
  readQuotaSnapshotFromError,
  resolveQuotaDisplayState,
} from '@/utils/quotaSnapshot'
import { formatTenantClock, tenantDayRelation } from '@/utils/quotaTime'
import { ApiError, NetworkError } from '@/utils/request'
import { QUOTA_RAIL_ID, QUOTA_REMIND_THRESHOLD } from '@/utils/uiConstants'

import { jsonResponse, setupPinia, stubLocation } from './helpers'

/**
 * M3.1 每日对话额度（api-spec.md §7.15 / design-system.md §15 / AC-QUOTA-010~013）。
 *
 * 🔴 本 spec 守护的纪律：
 *   1. 快照恰 9 键，缺键即视为非法形态；🔴 前端不含任何额度默认值（无 50、无 3）
 *   2. `30070` 与 `10005` 物理隔离：日额度用尽 🔴 不进入 QPM 倒计时（K5）
 *   3. 🔴 匿名（uid=null）不请求、不展示、不占位、不骨架（K12 反向断言）
 *   4. 🔴 跨租户 / 跨用户切换立即清空快照，且丢弃"主体已变更"的在途响应
 *   5. 四态优先级：exhausted > rateLimited > unlimited/loading/unavailable > lastOne > low > normal
 *   6. 🔴 重置时刻按响应 `timezone` 渲染（含 DST 非 00:00 的真实时刻），不按浏览器时区推算
 *   7. 用尽只禁用「发送 + Enter」：输入、草稿、停止生成一律不受影响
 *   8. 状态切换不改变 Composer 布局：四套模板恒在 DOM 中，恒只有一个激活
 */
const FAKE_TIMER_OPTIONS: Parameters<typeof vi.useFakeTimers>[0] = {
  toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout', 'Date'],
}

/** 权威快照样例（🔴 数值是测试夹具，不是前端默认值）。 */
function snapshotFixture(overrides: Partial<QuotaSnapshot> = {}): QuotaSnapshot {
  return {
    enabled: true,
    limit: 50,
    used: 12,
    remaining: 38,
    status: 'available',
    periodStart: '2026-08-13T16:00:00.000Z',
    resetsAt: '2026-08-14T16:00:00.000Z',
    timezone: 'Asia/Shanghai',
    asOf: '2026-08-14T03:21:07.412Z',
    ...overrides,
  }
}

function readyView(overrides: Partial<QuotaSnapshot> = {}): QuotaView {
  return { phase: 'ready', snapshot: snapshotFixture(overrides) }
}

function quotaResponse(snapshot: QuotaSnapshot): Response {
  return jsonResponse({ code: ERROR_CODE.SUCCESS, message: 'success', data: snapshot })
}

describe('parseQuotaSnapshot · 恰 9 键，缺一即非法', () => {
  it('合法快照原样解析', () => {
    expect(parseQuotaSnapshot(snapshotFixture())).toEqual(snapshotFixture())
  })

  it('🔴 缺任一必备键一律返回 null（绝不用局部字段拼快照）', () => {
    const keys = Object.keys(snapshotFixture())
    keys.forEach((key) => {
      const partial = { ...snapshotFixture() } as Record<string, unknown>
      delete partial[key]
      expect(parseQuotaSnapshot(partial)).toBeNull()
    })
  })

  it('未启用时 limit / remaining 为 null 且 status=unlimited', () => {
    const parsed = parseQuotaSnapshot(
      snapshotFixture({ enabled: false, limit: null, remaining: null, status: 'unlimited' }),
    )

    expect(parsed?.status).toBe('unlimited')
    expect(parsed?.limit).toBeNull()
    // 🔴 未启用时 used 仍是真实已结算数，不伪造 0
    expect(parsed?.used).toBe(12)
  })

  it('未知 status 按 enabled / remaining 归一，不中断展示', () => {
    const parsed = parseQuotaSnapshot({ ...snapshotFixture(), status: 'weird' })

    expect(parsed?.status).toBe('available')
  })

  it('非对象 / null / 类型不符返回 null', () => {
    expect(parseQuotaSnapshot(null)).toBeNull()
    expect(parseQuotaSnapshot('nope')).toBeNull()
    expect(parseQuotaSnapshot({ ...snapshotFixture(), used: '12' })).toBeNull()
    expect(parseQuotaSnapshot({ ...snapshotFixture(), timezone: '' })).toBeNull()
  })

  it('🔴 快照类型不含 retryAfterSeconds：即便后端误传也不会被消费', () => {
    const parsed = parseQuotaSnapshot({ ...snapshotFixture(), retryAfterSeconds: 30 })

    expect(parsed).not.toBeNull()
    expect(Object.keys(parsed ?? {})).not.toContain('retryAfterSeconds')
  })
})

describe('readQuotaSnapshotFromError · 30070 与 10005 严格分离', () => {
  it('30070 的 data 被解析为额度快照', () => {
    const exhausted = snapshotFixture({ used: 50, remaining: 0, status: 'exhausted' })
    const error = new ApiError(ERROR_CODE.DAILY_QUOTA_EXHAUSTED, '今日额度已用完', exhausted)

    expect(readQuotaSnapshotFromError(error)).toEqual(exhausted)
  })

  it('🔴 10005 不产生额度快照；30070 不启动 QPM 倒计时（K5 双向断言）', () => {
    setupPinia()
    const rateLimit = useRateLimitStore()
    const quotaError = new ApiError(
      ERROR_CODE.DAILY_QUOTA_EXHAUSTED,
      '今日额度已用完',
      snapshotFixture({ used: 50, remaining: 0, status: 'exhausted' }),
    )

    expect(readQuotaSnapshotFromError(new ApiError(ERROR_CODE.RATE_LIMITED, '太频繁', { retryAfterSeconds: 30 }))).toBeNull()
    expect(rateLimit.startFromError(quotaError)).toBe(false)
    expect(rateLimit.waiting).toBe(false)
    expect(rateLimit.remainingSeconds).toBe(0)
  })

  it('非 ApiError / 载荷非法返回 null', () => {
    expect(readQuotaSnapshotFromError(new NetworkError('断网'))).toBeNull()
    expect(
      readQuotaSnapshotFromError(new ApiError(ERROR_CODE.DAILY_QUOTA_EXHAUSTED, 'x', { used: 50 })),
    ).toBeNull()
  })
})

describe('resolveQuotaDisplayState · 四态判定优先级', () => {
  it('匿名恒 hidden（不请求、不展示）', () => {
    expect(resolveQuotaDisplayState({ phase: 'hidden', snapshot: null }, 0)).toBe('hidden')
    expect(resolveQuotaDisplayState({ phase: 'hidden', snapshot: null }, 30)).toBe('hidden')
  })

  it('🔴 日额度用尽优先于 QPM（用尽期间不得显示秒级倒计时）', () => {
    const view = readyView({ used: 50, remaining: 0, status: 'exhausted' })

    expect(resolveQuotaDisplayState(view, 0)).toBe('exhausted')
    expect(resolveQuotaDisplayState(view, 30)).toBe('exhausted')
  })

  it('QPM 优先于偏低 / 正常 / 加载', () => {
    expect(resolveQuotaDisplayState(readyView(), 30)).toBe('rateLimited')
    expect(resolveQuotaDisplayState({ phase: 'loading', snapshot: null }, 30)).toBe('rateLimited')
  })

  it('🔴 剩余次数提醒只由绝对阈值决定：remaining < 10 才展示，与总量无关', () => {
    // remaining=9 → low（提醒）；remaining=10 → hidden（充足，不展示）
    expect(resolveQuotaDisplayState(readyView({ remaining: 9, used: 41 }), 0)).toBe('low')
    expect(resolveQuotaDisplayState(readyView({ remaining: 10, used: 40 }), 0)).toBe('hidden')
    // 大总量同样成立：绝对阈值与 limit 无关
    expect(
      resolveQuotaDisplayState(readyView({ limit: 100, remaining: 9, used: 91 }), 0),
    ).toBe('low')
    expect(
      resolveQuotaDisplayState(readyView({ limit: 100, remaining: 10, used: 90 }), 0),
    ).toBe('hidden')
    // remaining=1 → lastOne（阈值内最高提醒等级）
    expect(resolveQuotaDisplayState(readyView({ remaining: 1, used: 49 }), 0)).toBe('lastOne')
    // 阈值常量本身必须是 10（🔴 不出现代码默认 50 或比例推断）
    expect(QUOTA_REMIND_THRESHOLD).toBe(10)
  })

  it('unlimited / loading / unavailable 各自成态', () => {
    expect(
      resolveQuotaDisplayState(
        readyView({ enabled: false, limit: null, remaining: null, status: 'unlimited' }),
        0,
      ),
    ).toBe('unlimited')
    expect(resolveQuotaDisplayState({ phase: 'loading', snapshot: null }, 0)).toBe('loading')
    expect(resolveQuotaDisplayState({ phase: 'unavailable', snapshot: null }, 0)).toBe('unavailable')
  })

  it('isQuotaExhausted：未启用恒不视为用尽', () => {
    expect(
      isQuotaExhausted(snapshotFixture({ enabled: false, limit: null, remaining: null, status: 'unlimited' })),
    ).toBe(false)
    expect(isQuotaExhausted(null)).toBe(false)
    expect(isQuotaExhausted(snapshotFixture({ remaining: 0, used: 50, status: 'exhausted' }))).toBe(true)
  })
})

describe('quotaTime · 租户时区渲染（🔴 禁止浏览器本地时区推算）', () => {
  it('同一 UTC 时刻在不同租户时区显示不同时刻', () => {
    const instant = '2026-08-14T16:00:00.000Z'

    expect(formatTenantClock(instant, 'Asia/Shanghai')).toBe('00:00')
    expect(formatTenantClock(instant, 'America/New_York')).toBe('12:00')
  })

  it('🔴 DST 使当地日首为 01:00 时如实显示 01:00（不写死 00:00）', () => {
    // America/Havana 2026-03-08 当地 00:00 不存在（spring-forward），日首为 01:00
    expect(formatTenantClock('2026-03-08T05:00:00.000Z', 'America/Havana')).toBe('01:00')
  })

  it('🔴 “今日/明日”在租户时区下比较：同一对时刻在两个时区得出不同结论', () => {
    const now = Date.parse('2026-08-14T15:00:00.000Z')
    const resetsAt = '2026-08-14T16:00:00.000Z'

    // 上海：当前 23:00（8/14），重置在 8/15 00:00 → 明日
    expect(tenantDayRelation(resetsAt, 'Asia/Shanghai', now)).toBe('tomorrow')
    // 纽约：当前 11:00（8/14），重置在 8/14 12:00 → 今日
    expect(tenantDayRelation(resetsAt, 'America/New_York', now)).toBe('today')
  })

  it('🔴 时区非法时返回 null（绝不回落浏览器时区）', () => {
    expect(formatTenantClock('2026-08-14T16:00:00.000Z', 'Asia/Atlantis')).toBeNull()
    expect(tenantDayRelation('2026-08-14T16:00:00.000Z', 'Asia/Atlantis')).toBeNull()
  })
})

describe('quota Store · 权威快照与重取路径', () => {
  let fetchMock: ReturnType<typeof vi.fn>
  let errorSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    setupPinia()
    localStorage.clear()
    stubLocation()
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  afterEach(() => {
    useQuotaStore().deactivate()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  function stubFetch(...responses: readonly Response[]): void {
    fetchMock = vi.fn()
    responses.forEach((response) => fetchMock.mockResolvedValueOnce(response))
    fetchMock.mockResolvedValue(responses[responses.length - 1])
    vi.stubGlobal('fetch', fetchMock)
  }

  it('🔴 匿名（uid=null）不请求、不展示、无占位（K12 反向断言）', async () => {
    stubFetch(quotaResponse(snapshotFixture()))
    const store = useQuotaStore()

    store.bindSubject('gift', null)
    await store.load()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(store.visible).toBe(false)
    expect(store.phase).toBe('hidden')
    expect(store.snapshot).toBeNull()
  })

  it('登录后绑定主体即拉取零参数的 /api/v1/me/quota', async () => {
    stubFetch(quotaResponse(snapshotFixture()))
    const store = useQuotaStore()

    store.bindSubject('gift', '10086')
    await flushPromises()

    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/api/v1/me/quota')
    // 🔴 零参数：不得出现 tenantId / uid / date
    expect(String(fetchMock.mock.calls[0]?.[0])).not.toContain('?')
    expect(store.phase).toBe('ready')
    expect(store.snapshot?.remaining).toBe(38)
    expect(store.exhausted).toBe(false)
  })

  it('🔴 切换租户立即清空快照，且丢弃旧主体的在途响应（跨租户隔离）', async () => {
    const resolvers: Array<(response: Response) => void> = []
    fetchMock = vi.fn(() => new Promise<Response>((resolve) => resolvers.push(resolve)))
    vi.stubGlobal('fetch', fetchMock)
    const store = useQuotaStore()

    store.bindSubject('gift', '10086')
    store.bindSubject('redbook', '10086')

    // 快照在新主体数据返回前必须已经清空
    expect(store.snapshot).toBeNull()
    expect(store.phase).toBe('loading')

    // gift 的响应迟到：🔴 绝不写进 redbook 的视图
    resolvers[0](quotaResponse(snapshotFixture({ used: 50, remaining: 0, status: 'exhausted' })))
    await flushPromises()
    expect(store.snapshot).toBeNull()
    expect(store.exhausted).toBe(false)

    resolvers[1](quotaResponse(snapshotFixture({ used: 1, remaining: 49 })))
    await flushPromises()
    expect(store.snapshot?.remaining).toBe(49)
  })

  it('登出（reset）清空快照，不残留上一主体数值', async () => {
    stubFetch(quotaResponse(snapshotFixture()))
    const store = useQuotaStore()
    store.bindSubject('gift', '10086')
    await flushPromises()

    store.reset()

    expect(store.snapshot).toBeNull()
    expect(store.visible).toBe(false)
    expect(store.phase).toBe('hidden')
  })

  it('查询失败进入 unavailable（不展示臆造数值），重试可恢复', async () => {
    stubFetch(
      jsonResponse({ code: ERROR_CODE.INTERNAL_ERROR, message: '系统繁忙' }),
      quotaResponse(snapshotFixture()),
    )
    const store = useQuotaStore()

    store.bindSubject('gift', '10086')
    await flushPromises()
    expect(store.phase).toBe('unavailable')
    expect(store.snapshot).toBeNull()
    expect(errorSpy).toHaveBeenCalled()

    await store.load()
    expect(store.phase).toBe('ready')
  })

  it('🔴 快照形态非法（缺键）按不可用处理，不拼凑数值', async () => {
    stubFetch(jsonResponse({ code: ERROR_CODE.SUCCESS, message: 'success', data: { used: 50 } }))
    const store = useQuotaStore()

    store.bindSubject('gift', '10086')
    await flushPromises()

    expect(store.phase).toBe('unavailable')
    expect(store.snapshot).toBeNull()
  })

  it('🔴 30070 拒绝响应自带快照 → 立即进入用尽态并禁用发送', async () => {
    stubFetch(quotaResponse(snapshotFixture()))
    const store = useQuotaStore()
    store.bindSubject('gift', '10086')
    await flushPromises()
    expect(store.exhausted).toBe(false)

    const consumed = store.applyRejection(
      new ApiError(
        ERROR_CODE.DAILY_QUOTA_EXHAUSTED,
        '今日额度已用完',
        snapshotFixture({ used: 50, remaining: 0, status: 'exhausted' }),
      ),
    )

    expect(consumed).toBe(true)
    expect(store.exhausted).toBe(true)
    expect(store.snapshot?.status).toBe('exhausted')
    // 10005 不被本入口消费
    expect(store.applyRejection(new ApiError(ERROR_CODE.RATE_LIMITED, '太频繁', { retryAfterSeconds: 9 }))).toBe(false)
  })

  it('生成结算后重取权威快照，并写入跨标签页校准信号', async () => {
    stubFetch(
      quotaResponse(snapshotFixture()),
      quotaResponse(snapshotFixture({ used: 13, remaining: 37 })),
    )
    const store = useQuotaStore()
    store.bindSubject('gift', '10086')
    await flushPromises()

    store.refreshAfterSettlement()
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(store.snapshot?.used).toBe(13)
    expect(localStorage.getItem('albedo:quota:sync')).not.toBeNull()
  })

  it('恢复前台（visibilitychange）重新查询权威快照', async () => {
    stubFetch(
      quotaResponse(snapshotFixture()),
      quotaResponse(snapshotFixture({ used: 20, remaining: 30 })),
    )
    const store = useQuotaStore()
    store.activate()
    store.bindSubject('gift', '10086')
    await flushPromises()

    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(store.snapshot?.remaining).toBe(30)
  })

  it('跨标签页信号（storage 事件）触发重新查询', async () => {
    stubFetch(
      quotaResponse(snapshotFixture()),
      quotaResponse(snapshotFixture({ used: 50, remaining: 0, status: 'exhausted' })),
    )
    const store = useQuotaStore()
    store.activate()
    store.bindSubject('gift', '10086')
    await flushPromises()

    window.dispatchEvent(new StorageEvent('storage', { key: 'albedo:quota:sync', newValue: '1' }))
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(store.exhausted).toBe(true)
  })

  it('🔴 到达 resetsAt 后重新查询（等待时长由服务端 resetsAt 反推，不用固定 86400s）', async () => {
    vi.useFakeTimers(FAKE_TIMER_OPTIONS)
    vi.setSystemTime(Date.parse('2026-08-14T15:59:59.500Z'))
    stubFetch(
      quotaResponse(snapshotFixture({ used: 50, remaining: 0, status: 'exhausted' })),
      quotaResponse(
        snapshotFixture({
          used: 0,
          remaining: 50,
          status: 'available',
          periodStart: '2026-08-14T16:00:00.000Z',
          resetsAt: '2026-08-15T16:00:00.000Z',
        }),
      ),
    )
    const store = useQuotaStore()
    store.activate()
    store.bindSubject('gift', '10086')
    await flushPromises()
    expect(store.exhausted).toBe(true)

    await vi.advanceTimersByTimeAsync(2000)

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(store.exhausted).toBe(false)
    expect(store.snapshot?.resetsAt).toBe('2026-08-15T16:00:00.000Z')
  })
})

describe('QuotaStatusRail · 四态渲染与布局稳定', () => {
  beforeEach(() => {
    vi.useFakeTimers(FAKE_TIMER_OPTIONS)
    vi.setSystemTime(Date.parse('2026-08-14T03:00:00.000Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  function mountRail(quota: QuotaView, rateLimitRemaining = 0) {
    return mount(QuotaStatusRail, { props: { quota, rateLimitRemaining } })
  }

  it('🔴 匿名不挂载整个状态轨（无请求、无显示、无占位、无骨架）', () => {
    const wrapper = mountRail({ phase: 'hidden', snapshot: null })

    expect(wrapper.find('.quota-rail').exists()).toBe(false)
    expect(wrapper.text()).toBe('')
    expect(wrapper.find('.quota-panel').exists()).toBe(false)
  })

  it('剩余次数偏低（<10）展示剩余、已用/总量与租户时区重置时刻', () => {
    const wrapper = mountRail(readyView({ remaining: 9, used: 41 }))

    const active = wrapper.find('.quota-panel.is-active')
    expect(active.classes()).toContain('quota-panel--count')
    expect(active.text()).toContain(zhCN.chat.quota.remaining.replace('{remaining}', '9'))
    expect(active.text()).toContain('已用 41/50')
    // 明日 00:00（租户时区）重置 —— 时刻按 Asia/Shanghai 渲染
    expect(active.text()).toContain('明日 00:00')
    expect(active.text()).toContain(zhCN.chat.quota.tenantTimezone)
  })

  it('🔴 剩余充足（≥10）时不挂载状态轨（释放底部对话空间）', () => {
    const wrapper = mountRail(readyView({ remaining: 38 }))

    expect(wrapper.find('.quota-rail').exists()).toBe(false)
    expect(wrapper.find('.quota-panel').exists()).toBe(false)
    expect(wrapper.text()).toBe('')
  })

  it('🔴 四套模板恒在 DOM 中且恒只有一个激活（Composer 不跳动）', () => {
    const states: readonly QuotaView[] = [
      readyView({ remaining: 9, used: 41 }),
      readyView({ remaining: 1, used: 49 }),
      readyView({ used: 50, remaining: 0, status: 'exhausted' }),
      { phase: 'loading', snapshot: null },
      { phase: 'unavailable', snapshot: null },
    ]

    states.forEach((quota) => {
      const wrapper = mountRail(quota, 0)
      expect(wrapper.findAll('.quota-panel')).toHaveLength(4)
      expect(wrapper.findAll('.quota-panel.is-active')).toHaveLength(1)
      // 非激活模板保留在文档流（参与最大尺寸计算）但对读屏隐藏
      wrapper.findAll('.quota-panel').forEach((panel) => {
        if (panel.classes().includes('is-active')) {
          expect(panel.attributes('aria-hidden')).toBeUndefined()
        } else {
          expect(panel.attributes('aria-hidden')).toBe('true')
        }
      })
    })
  })

  it('偏低 / 仅剩一次使用 warning 语义 + 不同图标（不只靠颜色区分）', () => {
    const low = mountRail(readyView({ remaining: 8, used: 42 }))
    expect(low.find('.quota-panel--count .quota-line--main').classes()).toContain('is-warning')
    expect(low.findAll('svg').length).toBeGreaterThan(0)

    const last = mountRail(readyView({ remaining: 1, used: 49 }))
    expect(last.find('.quota-panel--count').classes()).toContain('is-last-one')
    expect(last.find('.quota-panel.is-active').text()).toContain(zhCN.chat.quota.lastOne)
  })

  it('QPM 限流中：秒级等待文案 + 保留每日副信息，🔴 不出现任何阈值语义', () => {
    const wrapper = mountRail(readyView(), 30)

    const active = wrapper.find('.quota-panel.is-active')
    expect(active.classes()).toContain('quota-panel--rate')
    expect(active.text()).toContain(zhCN.chat.quota.rateLimited.replace('{remaining}', '30'))
    expect(active.text()).toContain('已用 12/50')
    expect(wrapper.text()).not.toContain('每分钟')
  })

  it('🔴 可见秒数容器 aria-hidden，倒计时不逐秒进入 live region', async () => {
    const wrapper = mountRail(readyView(), 30)
    const announced = wrapper.find('[role="status"]').text()

    expect(wrapper.find('.quota-panel--rate .quota-line--main').attributes('aria-hidden')).toBe('true')
    expect(announced).toBe(
      zhCN.chat.quota.a11yRateLimited.replace('{remaining}', '30'),
    )

    await wrapper.setProps({ rateLimitRemaining: 29 })

    expect(wrapper.find('.quota-panel--rate').text()).toContain('29')
    expect(wrapper.find('[role="status"]').text()).toBe(announced)
  })

  it('QPM 结束播报一次“可以继续发送”，🔴 绝不自动重发', async () => {
    const wrapper = mountRail(readyView({ remaining: 9, used: 41 }), 1)

    await wrapper.setProps({ rateLimitRemaining: 0 })

    expect(wrapper.find('[role="status"]').text()).toBe(zhCN.chat.quota.a11yRateLimitRecovered)
    expect(wrapper.find('.quota-panel.is-active').text()).toContain(
      zhCN.chat.quota.rateLimitRecovered,
    )
  })

  it('🔴 日额度用尽优先于 QPM：只出现重置时刻与联系管理员，无秒级倒计时', () => {
    const wrapper = mountRail(readyView({ used: 50, remaining: 0, status: 'exhausted' }), 30)

    const active = wrapper.find('.quota-panel.is-active')
    expect(active.classes()).toContain('quota-panel--exhausted')
    expect(active.text()).toContain(zhCN.chat.quota.exhausted)
    expect(active.text()).toContain(zhCN.chat.quota.contactAdmin)
    expect(active.text()).toContain('明日 00:00')
    expect(active.text()).not.toContain(zhCN.chat.quota.rateLimited.replace('{remaining}', '30'))
  })

  it('unlimited / loading / unavailable 使用补充状态模板；失败可局部重试', async () => {
    const unlimited = mountRail(
      readyView({ enabled: false, limit: null, remaining: null, status: 'unlimited' }),
    )
    expect(unlimited.find('.quota-panel.is-active').text()).toContain(zhCN.chat.quota.unlimited)

    const loading = mountRail({ phase: 'loading', snapshot: null })
    expect(loading.find('.quota-panel.is-active').text()).toContain(zhCN.chat.quota.loading)

    const failed = mountRail({ phase: 'unavailable', snapshot: null })
    expect(failed.find('.quota-panel.is-active').text()).toContain(zhCN.chat.quota.unavailable)
    await failed.find('.quota-retry').trigger('click')
    expect(failed.emitted('retry')).toHaveLength(1)
  })

  it('🔴 播报节点为 role=status + polite + atomic，且不是 Dialog / alert', () => {
    const wrapper = mountRail(readyView({ remaining: 9, used: 41 }))

    const live = wrapper.find('[role="status"]')
    expect(live.attributes('aria-live')).toBe('polite')
    expect(live.attributes('aria-atomic')).toBe('true')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    // 状态轨本体是可命名的分组（供 aria-describedby 关联），🔴 不抢焦点、不是浮层
    const rail = wrapper.find('.quota-rail')
    expect(rail.attributes('role')).toBe('group')
    expect(rail.attributes('aria-label')).toBe(zhCN.chat.quota.railLabel)
    expect(rail.attributes('tabindex')).toBeUndefined()
  })

  it('初次加载不主动播报；额度真实变化才 polite 一次，且可访问描述含真实 IANA 名称', async () => {
    const wrapper = mountRail(readyView({ remaining: 9, used: 41 }))
    expect(wrapper.find('[role="status"]').text()).toBe('')

    await wrapper.setProps({ quota: readyView({ used: 42, remaining: 8 }) })

    const announced = wrapper.find('[role="status"]').text()
    expect(announced).toContain('8')
    expect(announced).toContain('Asia/Shanghai')

    // 同 status + remaining 再次刷新（仅 asOf 变化）不重复播报
    await wrapper.setProps({
      quota: readyView({ used: 42, remaining: 8, asOf: '2026-08-14T03:30:00.000Z' }),
    })
    expect(wrapper.find('[role="status"]').text()).toBe(announced)
  })

  it('从用尽恢复时播报“已恢复”（发送恢复但不自动聚焦、不自动发送）', async () => {
    const wrapper = mountRail(readyView({ used: 50, remaining: 0, status: 'exhausted' }))

    await wrapper.setProps({ quota: readyView({ used: 45, remaining: 5 }) })

    expect(wrapper.find('[role="status"]').text()).toBe(
      zhCN.chat.quota.restored.replace('{remaining}', '5'),
    )
  })

  it('🔴 时区非法时省略重置段，不回落浏览器时区', () => {
    const wrapper = mountRail(readyView({ remaining: 9, used: 12, timezone: 'Asia/Atlantis' }))

    const active = wrapper.find('.quota-panel.is-active')
    expect(active.text()).toContain('已用 12/50')
    expect(active.text()).not.toContain('00:00')
  })
})

describe('ChatComposer · 日额度用尽的输入与发送表达', () => {
  const baseProps = {
    placeholder: '输入你的问题',
    tenantId: 't-1',
    maxChars: 2000,
    minChars: 1,
    generating: false,
    disabled: false,
  }

  beforeEach(() => {
    localStorage.clear()
    stubLocation()
  })

  afterEach(() => {
    document.body.innerHTML = ''
    vi.unstubAllGlobals()
  })

  it('🔴 用尽后输入可编辑、草稿保留，仅发送按钮与 Enter 发送被禁用', async () => {
    const wrapper = mount(ChatComposer, {
      props: {
        ...baseProps,
        quota: readyView({ used: 50, remaining: 0, status: 'exhausted' }),
        quotaExhausted: true,
      },
      attachTo: document.body,
    })
    const textarea = wrapper.find('textarea')

    await textarea.setValue('用尽后仍要能整理问题')
    await textarea.trigger('input')

    expect(textarea.attributes('disabled')).toBeUndefined()
    expect((textarea.element as HTMLTextAreaElement).value).toBe('用尽后仍要能整理问题')
    expect(localStorage.getItem('albedo:draft:t-1:localhost:5173')).toBe('用尽后仍要能整理问题')
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined()

    await wrapper.find('form').trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('🔴 用尽期间「停止生成」不受影响', async () => {
    const wrapper = mount(ChatComposer, {
      props: {
        ...baseProps,
        generating: true,
        quota: readyView({ used: 50, remaining: 0, status: 'exhausted' }),
        quotaExhausted: true,
      },
      attachTo: document.body,
    })

    const stopButton = wrapper.find('button')
    expect(stopButton.attributes('disabled')).toBeUndefined()
    await stopButton.trigger('click')
    expect(wrapper.emitted('stop')).toHaveLength(1)
  })

  it('用尽说明通过 aria-describedby 关联（🔴 持久 inline，不用 Tooltip / Toast）', () => {
    const wrapper = mount(ChatComposer, {
      props: {
        ...baseProps,
        quota: readyView({ used: 50, remaining: 0, status: 'exhausted' }),
        quotaExhausted: true,
      },
      attachTo: document.body,
    })

    expect(wrapper.find('textarea').attributes('aria-describedby')).toContain(QUOTA_RAIL_ID)
    expect(wrapper.find('button[type="submit"]').attributes('aria-describedby')).toContain(
      QUOTA_RAIL_ID,
    )
    expect(wrapper.find(`#${QUOTA_RAIL_ID}`).exists()).toBe(true)
  })

  it('🔴 状态轨挂载时不再渲染 RateLimitNote（两套限流皮肤不得并存）', () => {
    const wrapper = mount(ChatComposer, {
      props: { ...baseProps, quota: readyView(), rateLimitRemaining: 30 },
      attachTo: document.body,
    })

    expect(wrapper.find('.rate-note').exists()).toBe(false)
    expect(wrapper.find('.quota-panel--rate.is-active').exists()).toBe(true)
  })

  it('匿名（无额度视图）时沿用既有 RateLimitNote 呈现，状态轨不挂载', () => {
    const wrapper = mount(ChatComposer, {
      props: { ...baseProps, rateLimitRemaining: 30 },
      attachTo: document.body,
    })

    expect(wrapper.find('.quota-rail').exists()).toBe(false)
    expect(wrapper.find('.rate-note').exists()).toBe(true)
  })

  it('额度充足时发送按钮可用（额度不改变既有可发送判定）', async () => {
    const wrapper = mount(ChatComposer, {
      props: { ...baseProps, quota: readyView() },
      attachTo: document.body,
    })

    await wrapper.find('textarea').setValue('正常发送')

    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })
})

describe('额度文案纪律', () => {
  it('design-system §15.10 要求的 locale key 全部存在', () => {
    const required = [
      'chat.quota.railLabel',
      'chat.quota.remaining',
      'chat.quota.low',
      'chat.quota.lastOne',
      'chat.quota.usedTotal',
      'chat.quota.resetAt',
      'chat.quota.tenantTimezone',
      'chat.quota.dayToday',
      'chat.quota.dayTomorrow',
      'chat.quota.exhausted',
      'chat.quota.contactAdmin',
      'chat.quota.unlimited',
      'chat.quota.loading',
      'chat.quota.unavailable',
      'chat.quota.retry',
      'chat.quota.restored',
      'chat.quota.rateLimited',
      'chat.quota.rateLimitRecovered',
      'chat.quota.a11ySummary',
      'chat.quota.a11yExhausted',
      'chat.quota.a11yRateLimited',
      'chat.quota.a11yRateLimitRecovered',
      'errors.dailyQuotaExhausted.title',
      'errors.dailyQuotaExhausted.description',
    ]

    expect(required.filter((key) => t(key) === key)).toEqual([])
  })

  it('🔴 QPM 与日额度文案语义明确不同（秒级等待 vs 当日重置）', () => {
    expect(zhCN.chat.quota.rateLimited).toContain('{remaining}')
    expect(zhCN.chat.quota.exhausted).not.toContain('{remaining}')
    expect(zhCN.chat.quota.exhausted).not.toContain('秒')
    expect(zhCN.errors.dailyQuotaExhausted.description).not.toContain('秒')
  })

  it('🔴 额度文案中不出现平台默认阈值数字（3 / 50 一律来自服务端快照）', () => {
    // “仅剩 1 次”的 1 是整数额度的最后一个正值（§15.3 措辞），不是阈值，
    // 故只拦多位数字：任何 3 / 50 之类的策略数字都不得写进文案。
    Object.values(zhCN.chat.quota).forEach((text) => {
      expect(text).not.toMatch(/\d{2,}/)
    })
    expect(JSON.stringify(zhCN.chat.quota)).not.toContain('50')
  })
})
