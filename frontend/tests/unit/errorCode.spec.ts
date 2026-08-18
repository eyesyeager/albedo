import { describe, expect, it } from 'vitest'

import { AUTH_ERROR_CODES, ERROR_CODE, SITE_UNAVAILABLE_CODES } from '@/types/api'

/**
 * 错误码基线守护测试（框架 §十四 / docs/api-spec.md §2.2）。
 *
 * 目的：把"错误码纪律"变成可执行断言，防止后续实现悄悄回退。
 */
describe('错误码基线', () => {
  it('鉴权失效码恰好为 20000~20005', () => {
    expect([...AUTH_ERROR_CODES].sort((a, b) => a - b)).toEqual([
      20000, 20001, 20002, 20003, 20004, 20005,
    ])
  })

  it('禁止出现已废弃的 10002 / 40001', () => {
    const codes = Object.values(ERROR_CODE)
    expect(codes).not.toContain(10002)
    expect(codes).not.toContain(40001)
  })

  it('业务码不得占用耶瞳保留段 20000~20999', () => {
    const businessCodes = [
      ERROR_CODE.TENANT_NOT_FOUND,
      ERROR_CODE.TENANT_SUSPENDED,
      ERROR_CODE.TENANT_CONFIG_UNAVAILABLE,
      ERROR_CODE.TENANT_CONTEXT_MISSING,
      ERROR_CODE.VERSION_CONFLICT,
      ERROR_CODE.PUBLISH_VALIDATE_FAILED,
      ERROR_CODE.AGENT_UNAVAILABLE,
      ERROR_CODE.AGENT_DISABLED,
      ERROR_CODE.CONVERSATION_READONLY,
      ERROR_CODE.MESSAGE_TOO_LONG,
      ERROR_CODE.TOOL_DENIED,
      ERROR_CODE.TOOL_TIMEOUT,
      ERROR_CODE.MCP_UNAVAILABLE,
    ]
    businessCodes.forEach((code) => {
      expect(code).toBeGreaterThanOrEqual(30000)
      expect(code).toBeLessThan(40000)
    })
  })

  it('站点级不可用码为 30010 / 30011 / 30012', () => {
    expect([...SITE_UNAVAILABLE_CODES]).toEqual([30010, 30011, 30012])
  })

  it('成功码唯一为 0（禁止 200）', () => {
    expect(ERROR_CODE.SUCCESS).toBe(0)
  })
})
