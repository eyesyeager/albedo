import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, NetworkError } from '@/utils/request'
import request, { consumeTokenFromUrl } from '@/utils/request'

import { jsonResponse, stubLocation } from './helpers'

/**
 * 统一请求封装的鉴权契约测试（框架 §14.3 / api-spec §2.2）。
 *
 * 覆盖：token 注入、续期回写、20000~20005 清退整页跳 SSO、
 * 30000+ 业务错误不跳登录、成功解包 data、SSO 回跳清 URL。
 */
const TOKEN_KEY = 'authorization'
const AUTH_CODES = [20000, 20001, 20002, 20003, 20004, 20005]

describe('request · 鉴权与错误码分流', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('成功响应解包 data 并注入 authorization 请求头', async () => {
    localStorage.setItem(TOKEN_KEY, 'my-token')
    stubLocation()
    const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
      Promise.resolve(jsonResponse({ code: 0, message: 'success', data: { siteTitle: 'X' } })),
    )
    vi.stubGlobal('fetch', fetchMock)

    const data = await request.get<{ siteTitle: string }>('/api/v1/site/config')

    expect(data.siteTitle).toBe('X')
    const headers = (fetchMock.mock.calls[0][1]?.headers ?? {}) as Record<string, string>
    expect(headers.authorization).toBe('my-token')
  })

  it.each(AUTH_CODES)('code=%i 时清 token 并整页跳耶瞳 SSO（不进入 /login）', async (code) => {
    localStorage.setItem(TOKEN_KEY, 'bad-token')
    const stub = stubLocation('http://localhost:5173/c/1001')
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonResponse({ code, message: '鉴权失效' }))))

    const error = await request.get('/api/v1/me').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe(code)
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(stub.href).toContain('/OAuth2?clientId=')
    expect(stub.href).toContain(`redirectUrl=${encodeURIComponent('http://localhost:5173/c/1001')}`)
    expect(stub.href).not.toContain('/login')
  })

  it('20008（鉴权参数非法）不属于清退段：保留 token 且不跳转', async () => {
    localStorage.setItem(TOKEN_KEY, 'keep-token')
    const stub = stubLocation()
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonResponse({ code: 20008, message: '参数非法' }))))

    await request.get('/api/v1/me').catch(() => undefined)

    expect(localStorage.getItem(TOKEN_KEY)).toBe('keep-token')
    expect(stub.href).not.toContain('OAuth2')
  })

  it.each([10001, 10003, 10004, 30011, 30041, 50002, 50003])(
    'code=%i 属于业务/系统错误：抛 ApiError 且绝不跳登录',
    async (code) => {
      localStorage.setItem(TOKEN_KEY, 'keep-token')
      const stub = stubLocation()
      vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonResponse({ code, message: '业务错误' }))))

      const error = await request.get('/api/v1/conversations').catch((e: unknown) => e)

      expect(error).toBeInstanceOf(ApiError)
      expect((error as ApiError).code).toBe(code)
      expect(localStorage.getItem(TOKEN_KEY)).toBe('keep-token')
      expect(stub.href).not.toContain('OAuth2')
    },
  )

  it('响应头新 token 立即回写（auth-type=1 续期）', async () => {
    localStorage.setItem(TOKEN_KEY, 'old-token')
    stubLocation()
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(jsonResponse({ code: 0, message: 'success' }, { authorization: 'new-token' })),
      ),
    )

    await request.get('/api/v1/me')

    expect(localStorage.getItem(TOKEN_KEY)).toBe('new-token')
  })

  it('网络异常抛 NetworkError（不误判为鉴权失效）', async () => {
    stubLocation()
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('failed to fetch'))))

    const error = await request.get('/api/v1/me').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(NetworkError)
  })
})

describe('request · SSO 回跳处理（AC-AUTH-002）', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('解析 ?authorization 存入 localStorage 并立即从 URL 清除，保留其他安全参数', () => {
    stubLocation()
    const replaceState = vi.fn()
    vi.stubGlobal('history', { replaceState })
    Object.assign(location, { pathname: '/c/1001', search: '?authorization=jwt-token&from=share', hash: '' })

    const received = consumeTokenFromUrl()

    expect(received).toBe(true)
    expect(localStorage.getItem(TOKEN_KEY)).toBe('jwt-token')
    const cleanUrl = replaceState.mock.calls[0][2] as string
    expect(cleanUrl).toBe('/c/1001?from=share')
    expect(cleanUrl).not.toContain('authorization')
  })

  it('URL 中无 token 时不改写历史', () => {
    stubLocation()
    const replaceState = vi.fn()
    vi.stubGlobal('history', { replaceState })
    Object.assign(location, { pathname: '/', search: '', hash: '' })

    expect(consumeTokenFromUrl()).toBe(false)
    expect(replaceState).not.toHaveBeenCalled()
  })
})
