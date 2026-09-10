import { AxiosError, AxiosHeaders, type AxiosAdapter } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ApiError } from '@/api/errors'
import { configureUnauthorizedHandler, http, requestData } from '@/api/http'
import { readAuthSession, saveAuthSession } from '@/features/auth/session'

describe('HTTP client', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    configureUnauthorizedHandler(null)
  })

  it('uses the same-origin API prefix and decorates authenticated writes', async () => {
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
    let authorization: unknown
    let requestId: unknown
    const adapter: AxiosAdapter = async (config) => {
      authorization = config.headers.get('Authorization')
      requestId = config.headers.get('X-Request-Id')
      return {
        data: { code: 'OK', message: 'ok', data: 'accepted', traceId: 'trace-body' },
        status: 200,
        statusText: 'OK',
        headers: new AxiosHeaders(),
        config,
      }
    }

    const result = await requestData<string>({ method: 'POST', url: '/probe', adapter })

    expect(http.defaults.baseURL).toBe('/api')
    expect(authorization).toBe('Bearer signed-token')
    expect(requestId).toMatch(/^web-/)
    expect(result).toEqual({ data: 'accepted', traceId: 'trace-body' })
  })

  it('clears an authenticated session and reports the trace when the server returns 401', async () => {
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
    const unauthorizedHandler = vi.fn<(error: ApiError) => void>()
    configureUnauthorizedHandler(unauthorizedHandler)
    const adapter: AxiosAdapter = async (config) => {
      const response = {
        data: {
          code: 'COMMON_UNAUTHENTICATED',
          message: 'Unauthenticated',
          data: null,
          traceId: 'trace-401',
        },
        status: 401,
        statusText: 'Unauthorized',
        headers: new AxiosHeaders({ 'x-trace-id': 'trace-401' }),
        config,
      }
      throw new AxiosError('Request failed', AxiosError.ERR_BAD_REQUEST, config, undefined, response)
    }

    await expect(requestData({ method: 'GET', url: '/users/me', adapter })).rejects.toMatchObject({
      code: 'COMMON_UNAUTHENTICATED',
      status: 401,
      traceId: 'trace-401',
    })
    expect(readAuthSession()).toBeNull()
    expect(unauthorizedHandler).toHaveBeenCalledOnce()
  })

  it('keeps an ordinary invalid-login 401 inside the login form', async () => {
    const unauthorizedHandler = vi.fn<(error: ApiError) => void>()
    configureUnauthorizedHandler(unauthorizedHandler)
    const adapter: AxiosAdapter = async (config) => {
      const response = {
        data: {
          code: 'ACCOUNT_INVALID_CREDENTIALS',
          message: 'Invalid credentials',
          data: null,
          traceId: 'trace-login-failed',
        },
        status: 401,
        statusText: 'Unauthorized',
        headers: new AxiosHeaders(),
        config,
      }
      throw new AxiosError('Request failed', AxiosError.ERR_BAD_REQUEST, config, undefined, response)
    }

    await expect(requestData({ method: 'POST', url: '/auth/login', adapter })).rejects.toMatchObject({
      code: 'ACCOUNT_INVALID_CREDENTIALS',
      userMessage: '用户名或密码错误。',
    })
    expect(unauthorizedHandler).not.toHaveBeenCalled()
  })
})
