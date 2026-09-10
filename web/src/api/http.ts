import axios, { type AxiosRequestConfig } from 'axios'

import { ApiError, normalizeApiError } from '@/api/errors'
import { clearAuthSession, readAuthSession } from '@/features/auth/session'
import type { ApiResponse, ApiResult } from '@/types/api'

type UnauthorizedHandler = (error: ApiError) => void

let unauthorizedHandler: UnauthorizedHandler | null = null
let unauthorizedHandled = false

const WRITE_METHODS = new Set(['post', 'put', 'patch', 'delete'])
const ANONYMOUS_AUTH_PATHS = new Set(['/auth/login', '/auth/register'])

function createRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `web-${crypto.randomUUID()}`
  }

  return `web-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`
}

export function configureUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler
  unauthorizedHandled = false
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 10_000,
  headers: {
    Accept: 'application/json',
  },
})

http.interceptors.request.use(
  (config) => {
    const session = readAuthSession()
    if (session) {
      unauthorizedHandled = false
      config.headers.set('Authorization', `${session.tokenType} ${session.accessToken}`)
    }

    const method = config.method?.toLowerCase()
    if (method && WRITE_METHODS.has(method) && !config.headers.has('X-Request-Id')) {
      config.headers.set('X-Request-Id', createRequestId())
    }

    return config
  },
  undefined,
  { synchronous: true },
)

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const apiError = normalizeApiError(error)
    const requestUrl = axios.isAxiosError(error) ? error.config?.url : undefined
    const isAnonymousAuthRequest =
      typeof requestUrl === 'string' && ANONYMOUS_AUTH_PATHS.has(requestUrl)
    if (apiError.status === 401 && !isAnonymousAuthRequest && !unauthorizedHandled) {
      unauthorizedHandled = true
      clearAuthSession()
      unauthorizedHandler?.(apiError)
    }
    return Promise.reject(apiError)
  },
)

export async function requestData<T>(config: AxiosRequestConfig): Promise<ApiResult<T>> {
  const response = await http.request<ApiResponse<T>>(config)
  const body = response.data

  if (
    typeof body !== 'object' ||
    body === null ||
    typeof body.code !== 'string' ||
    typeof body.message !== 'string' ||
    !('data' in body)
  ) {
    throw new ApiError({
      code: 'CLIENT_INVALID_RESPONSE',
      status: response.status,
      userMessage: '服务返回了无法识别的数据。',
    })
  }

  const traceHeader = response.headers['x-trace-id']
  return {
    data: body.data,
    traceId:
      body.traceId?.trim() || (typeof traceHeader === 'string' ? traceHeader.trim() : null) || null,
  }
}
