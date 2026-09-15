import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import MessageErrorBlock from '@/components/chat/MessageErrorBlock.vue'
import MessageItem from '@/components/chat/MessageItem.vue'
import { t } from '@/locales'
import zhCN from '@/locales/zh-CN'
import { ERROR_CODE } from '@/types/api'
import type { ChatMessageView } from '@/types/chat'
import type { ToolCallStatusValue, ToolCallSummary } from '@/types/tool'
import {
  errorDisplayLevel,
  messageErrorText,
  toolErrorDescriptionKey,
} from '@/utils/toolErrors'

import { setupPinia } from './helpers'

/**
 * M3 错误码展示层级与文案映射（design-system.md §14.5 / api-spec.md §2.2）。
 *
 * 🔴 本 spec 守护的纪律：
 *   1. 能在工具节点解释的错误**不升级**为消息级块（避免同一错误两处重复）
 *   2. `timed_out + 30050`（确认等待超时，未执行）与 `30051` / `30056`
 *      （执行超时 / 结果未知）🔴 语义严格区分，且都不暗示"已成功"
 *   3. 所有 locale key 必须在 `locales/` 真实存在（🔴 绝不把 key 显示给用户）
 *   4. `30061` 属 M2-min 运维接口，design-system §14.5 明确**不在终端展示范围**
 *   5. 一律不使用全局 Toast / Dialog
 */
const TOOL_LEVEL_CODES = [
  ERROR_CODE.TOOL_DENIED, // 30050
  ERROR_CODE.TOOL_TIMEOUT, // 30051
  ERROR_CODE.MCP_UNAVAILABLE, // 30052
  ERROR_CODE.TOOL_ARGS_INVALID, // 30053
  ERROR_CODE.TOOL_RETRY_BLOCKED, // 30056
  ERROR_CODE.TOOL_EXECUTION_FAILED, // 30057
] as const

const MESSAGE_LEVEL_CODES = [
  ERROR_CODE.TOOL_LOOP_LIMIT_EXCEEDED, // 30054
  ERROR_CODE.RUNTIME_CONFIG_INVALID, // 30060
  ERROR_CODE.RATE_LIMITED, // 10005
] as const

function message(overrides: Partial<ChatMessageView> = {}): ChatMessageView {
  return {
    messageId: '5002',
    role: 'assistant',
    content: '',
    status: 'failed',
    attemptNo: 1,
    isCurrent: true,
    createdAt: '2026-08-13T02:20:11.000Z',
    clientId: 'c-1',
    streaming: false,
    errorMessage: null,
    errorCode: null,
    toolCalls: [],
    reasoning: '',
    segments: [],
    ...overrides,
  }
}

function toolCall(overrides: Partial<ToolCallSummary> = {}): ToolCallSummary {
  return {
    toolCallId: '9001',
    toolType: 'mcp',
    toolKey: 'weather:query',
    status: 'failed',
    round: 1,
    summary: '',
    argsSummary: '',
    resultSummary: '',
    truncated: false,
    errorCode: null,
    retryAfterSeconds: null,
    ...overrides,
  }
}

describe('toolErrorDescriptionKey · 工具状态条内的错误说明', () => {
  it('🔴 timed_out + 30051 = 执行超时，不暗示已成功', () => {
    const execTimeout = t(toolErrorDescriptionKey('timed_out', ERROR_CODE.TOOL_TIMEOUT))

    expect(toolErrorDescriptionKey('timed_out', ERROR_CODE.TOOL_TIMEOUT)).toBe(
      'errors.toolTimeout.description',
    )
    expect(execTimeout).not.toContain('成功')
    expect(execTimeout).not.toContain('已完成')
  })

  it('🔴 30056（结果未知）不等于执行超时，且禁止暗示成功', () => {
    const retryBlocked = t(toolErrorDescriptionKey('timed_out', ERROR_CODE.TOOL_RETRY_BLOCKED))

    expect(toolErrorDescriptionKey('failed', ERROR_CODE.TOOL_RETRY_BLOCKED)).toBe(
      'errors.toolRetryBlocked.description',
    )
    expect(retryBlocked).toBe(zhCN.errors.toolRetryBlocked.description)
    expect(retryBlocked).not.toBe(t('errors.toolTimeout.description'))
    expect(retryBlocked).not.toContain('成功')
  })

  it('denied + 30050 使用普通拒绝文案', () => {
    expect(toolErrorDescriptionKey('denied', ERROR_CODE.TOOL_DENIED)).toBe(
      'errors.toolDenied.description',
    )
  })

  it('每个工具级错误码都有真实存在的文案（🔴 绝不把 key 显示给用户）', () => {
    TOOL_LEVEL_CODES.forEach((code) => {
      const key = toolErrorDescriptionKey('failed', code)
      expect(key).not.toBe('')
      expect(t(key)).not.toBe(key)
    })
  })

  it('errorCode 为 null 或未登记码时不产生错误说明（不编造解释）', () => {
    expect(toolErrorDescriptionKey('succeeded', null)).toBe('')
    expect(toolErrorDescriptionKey('failed', 39999)).toBe('')
  })

  it('全部 7 种状态在 errorCode=null 时都不产生错误说明', () => {
    const statuses: readonly ToolCallStatusValue[] = [
      'pending',
      'running',
      'succeeded',
      'failed',
      'timed_out',
      'cancelled',
      'denied',
    ]

    statuses.forEach((status) => expect(toolErrorDescriptionKey(status, null)).toBe(''))
  })
})

describe('errorDisplayLevel · 展示层级判定', () => {
  it('30050~30053 / 30056 / 30057 有工具节点时留在工具状态条', () => {
    TOOL_LEVEL_CODES.forEach((code) => {
      expect(errorDisplayLevel(code, true)).toBe('tool')
    })
  })

  it('同一批错误码在无工具节点时升级为消息级（否则用户看不到任何解释）', () => {
    TOOL_LEVEL_CODES.forEach((code) => {
      expect(errorDisplayLevel(code, false)).toBe('message')
    })
  })

  it('30054 / 30060 / 10005 恒为消息级（即使已有工具节点）', () => {
    MESSAGE_LEVEL_CODES.forEach((code) => {
      expect(errorDisplayLevel(code, true)).toBe('message')
      expect(errorDisplayLevel(code, false)).toBe('message')
    })
  })

  it('🔴 30061 不在终端 M3 展示范围（design-system §14.5）：不产生工具层解释、无终端文案', () => {
    expect(errorDisplayLevel(ERROR_CODE.CACHE_INVALIDATION_FAILED, true)).toBe('message')
    expect(toolErrorDescriptionKey('failed', ERROR_CODE.CACHE_INVALIDATION_FAILED)).toBe('')
    // 🔴 不得为运维错误码新增终端 locale key
    expect(t('errors.cacheInvalidationFailed.title')).toBe('errors.cacheInvalidationFailed.title')
  })
})

describe('messageErrorText · 消息级文案映射', () => {
  it('30054 / 30060 / 10005 映射到各自 title + description，且文案真实存在', () => {
    const expected: Readonly<Record<number, string>> = {
      [ERROR_CODE.TOOL_LOOP_LIMIT_EXCEEDED]: 'errors.toolLoopLimit',
      [ERROR_CODE.RUNTIME_CONFIG_INVALID]: 'errors.runtimeConfigInvalid',
      [ERROR_CODE.RATE_LIMITED]: 'errors.rateLimited',
    }

    MESSAGE_LEVEL_CODES.forEach((code) => {
      const text = messageErrorText(code, false)
      expect(text).toEqual({
        titleKey: `${expected[code]}.title`,
        descriptionKey: `${expected[code]}.description`,
      })
      expect(t(text?.titleKey ?? '')).not.toBe(text?.titleKey)
      expect(t(text?.descriptionKey ?? '')).not.toBe(text?.descriptionKey)
    })
  })

  it('🔴 工具级错误在有工具节点时返回 null（不在消息层重复解释）', () => {
    TOOL_LEVEL_CODES.forEach((code) => {
      expect(messageErrorText(code, true)).toBeNull()
    })
  })

  it('工具级错误在无工具节点时补消息级文案', () => {
    expect(messageErrorText(ERROR_CODE.MCP_UNAVAILABLE, false)).toEqual({
      titleKey: 'errors.mcpUnavailable.title',
      descriptionKey: 'errors.mcpUnavailable.description',
    })
  })

  it('errorCode 为 null 或未登记码时无消息级文案（由服务端 message 兜底）', () => {
    expect(messageErrorText(null, false)).toBeNull()
    expect(messageErrorText(ERROR_CODE.CACHE_INVALIDATION_FAILED, false)).toBeNull()
    expect(messageErrorText(ERROR_CODE.UPSTREAM_UNAVAILABLE, false)).toBeNull()
  })
})

describe('MessageErrorBlock · 渲染层级与内容', () => {
  it('30054 渲染 locales 标题 + 说明，且是内联 role=status（不是 Toast / Dialog）', () => {
    const wrapper = mount(MessageErrorBlock, {
      props: {
        code: ERROR_CODE.TOOL_LOOP_LIMIT_EXCEEDED,
        message: '后端可展示语义',
        hasToolNode: true,
      },
    })

    expect(wrapper.find('.message-error-title').text()).toBe(zhCN.errors.toolLoopLimit.title)
    expect(wrapper.find('.message-error-desc').text()).toBe(zhCN.errors.toolLoopLimit.description)
    expect(wrapper.attributes('role')).toBe('status')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('10005 消息级块插值服务端剩余秒数', () => {
    const wrapper = mount(MessageErrorBlock, {
      props: {
        code: ERROR_CODE.RATE_LIMITED,
        message: '请求过于频繁',
        hasToolNode: false,
        retryAfterSeconds: 18,
      },
    })

    expect(wrapper.find('.message-error-desc').text()).toBe(
      zhCN.errors.rateLimited.description.replace('{remaining}', '18'),
    )
  })

  it('🔴 未登记文案的错误码退回服务端 message，绝不显示空块或 locale key', () => {
    const wrapper = mount(MessageErrorBlock, {
      props: {
        code: ERROR_CODE.UPSTREAM_UNAVAILABLE,
        message: '模型服务暂时不可用',
        hasToolNode: false,
      },
    })

    expect(wrapper.text()).toContain('模型服务暂时不可用')
    expect(wrapper.text()).not.toContain('errors.')
  })
})

describe('MessageItem · 错误分层不重复、不静默', () => {
  beforeEach(() => {
    setupPinia({ tool: { confirmWaitSeconds: 60 } })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('🔴 30050 已在工具状态条解释时不再渲染消息级块', () => {
    const wrapper = mount(MessageItem, {
      props: {
        message: message({
          errorCode: ERROR_CODE.TOOL_DENIED,
          errorMessage: '工具未获执行许可',
          toolCalls: [toolCall({ status: 'denied', errorCode: ERROR_CODE.TOOL_DENIED })],
        }),
        crossRole: false,
        readOnly: false,
      },
    })

    expect(wrapper.find('.message-error').exists()).toBe(false)
    expect(wrapper.find('.tool-bar-error').text()).toBe(zhCN.errors.toolDenied.description)
  })

  it('30054 无对应工具节点时渲染消息级块', () => {
    const wrapper = mount(MessageItem, {
      props: {
        message: message({
          errorCode: ERROR_CODE.TOOL_LOOP_LIMIT_EXCEEDED,
          errorMessage: '已达到调用轮次上限',
        }),
        crossRole: false,
        readOnly: false,
      },
    })

    expect(wrapper.find('.message-error-title').text()).toBe(zhCN.errors.toolLoopLimit.title)
  })

  it('🔴 未登记文案的失败消息必须有可读解释（不得静默无提示）', () => {
    const wrapper = mount(MessageItem, {
      props: {
        message: message({
          errorCode: ERROR_CODE.UPSTREAM_UNAVAILABLE,
          errorMessage: '模型服务暂时不可用',
        }),
        crossRole: false,
        readOnly: false,
      },
    })

    expect(wrapper.text()).toContain('模型服务暂时不可用')
  })

  it('🔴 30061 若意外出现在终端也不静默：退回服务端可展示语义', () => {
    const wrapper = mount(MessageItem, {
      props: {
        message: message({
          errorCode: ERROR_CODE.CACHE_INVALIDATION_FAILED,
          errorMessage: '部分缓存未失效',
        }),
        crossRole: false,
        readOnly: false,
      },
    })

    expect(wrapper.text()).toContain('部分缓存未失效')
    expect(wrapper.text()).not.toContain('errors.')
  })
})
