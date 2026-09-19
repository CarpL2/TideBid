import axios from 'axios'

import type { ApiResponse } from '@/types/api'

const USER_MESSAGES: Record<string, string> = {
  COMMON_INVALID_ARGUMENT: '请求内容不符合要求，请检查后重试。',
  COMMON_UNAUTHENTICATED: '登录状态已失效，请重新登录。',
  COMMON_FORBIDDEN: '当前账号没有执行此操作的权限。',
  COMMON_NOT_FOUND: '请求的资源不存在。',
  COMMON_METHOD_NOT_ALLOWED: '当前操作方式不受支持。',
  COMMON_CONFLICT: '当前操作与最新状态冲突，请刷新后重试。',
  COMMON_TOO_MANY_REQUESTS: '操作过于频繁，请稍后再试。',
  COMMON_SERVICE_UNAVAILABLE: '服务暂时不可用，请稍后重试。',
  COMMON_INTERNAL_ERROR: '服务处理失败，请稍后重试。',
  ACCOUNT_USERNAME_ALREADY_EXISTS: '该用户名已被注册。',
  ACCOUNT_INVALID_CREDENTIALS: '用户名或密码错误。',
  ACCOUNT_DISABLED: '该账号已被禁用。',
  AUCTION_STORAGE_UNAVAILABLE: '对象存储尚未配置或暂时不可用。',
  AUCTION_ASSET_INVALID: '拍品内容不符合上架要求。',
  AUCTION_ASSET_NOT_FOUND: '拍品不存在或已经不可访问。',
  AUCTION_AMOUNT_INVALID: '竞价金额必须为正数且最多保留两位小数。',
  AUCTION_TIME_INVALID: '场次时间不符合要求，请检查开始时间和持续时长。',
  AUCTION_IMAGE_INVALID: '图片不存在、已过期或不属于当前账号。',
  AUCTION_ASSET_ACCESS_DENIED: '你不能查看或修改该拍品。',
  AUCTION_ASSET_STATE_CONFLICT: '拍品状态或版本已经变化，请刷新后重试。',
  AUCTION_SUBMISSION_VERSION_CONFLICT: '该拍品已被其他管理员处理，请刷新审核队列。',
  AUCTION_INVALID: '竞价请求内容不符合要求。',
  AUCTION_NOT_FOUND: '竞价场次不存在或不可访问。',
  AUCTION_STATE_CONFLICT: '竞价场次状态已经变化，请刷新后重试。',
  AUCTION_NOT_STARTED: '竞价尚未开始。',
  AUCTION_ENDED: '本场竞价已经结束。',
  AUCTION_SELLER_CANNOT_PARTICIPATE: '卖家不能报名或竞拍自己的拍品。',
  AUCTION_REGISTRATION_NOT_FOUND: '报名记录不存在或不可访问。',
  AUCTION_REGISTRATION_CLOSED: '本场竞价的报名已经截止。',
  AUCTION_REGISTRATION_REQUIRED: '完成保证金报名后才能出价。',
  AUCTION_REGISTRATION_PENDING: '保证金仍在处理中，请稍后再试。',
  AUCTION_DEPOSIT_INSUFFICIENT: '可用余额不足，无法冻结本场保证金。',
  AUCTION_BID_AMOUNT_INVALID: '报价必须为正数且最多保留两位小数。',
  AUCTION_BID_TOO_LOW: '报价低于当前最低可接受价格。',
  AUCTION_BID_CONFLICT: '价格刚刚发生变化，请按最新最低报价重试。',
  AUCTION_IDEMPOTENCY_CONFLICT: '该请求编号已用于另一笔报价，请重新操作。',
  AUCTION_ACCOUNT_SERVICE_UNAVAILABLE: '保证金服务暂时不可用，报名状态将在后台恢复。',
  TRADE_ORDER_NOT_FOUND: '订单不存在或已经不可访问。',
  TRADE_ORDER_FORBIDDEN: '你不能查看或操作这笔订单。',
  TRADE_ORDER_NOT_PAYABLE: '订单当前不能支付，请刷新订单状态。',
  TRADE_PAYMENT_DEADLINE_EXPIRED: '订单支付期限已过，不能继续支付。',
  TRADE_PAYMENT_IDEMPOTENCY_CONFLICT: '本次支付请求与已有记录冲突，请刷新后重试。',
  TRADE_PAYMENT_CONCURRENT_CONFLICT: '另一笔支付正在处理中，请稍后刷新结果。',
}

export interface ApiErrorOptions {
  code: string
  status: number
  userMessage: string
  traceId?: string | null
  cause?: unknown
  data?: unknown
}

export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly userMessage: string
  readonly traceId: string | null
  readonly originalError: unknown
  readonly data: unknown

  constructor(options: ApiErrorOptions) {
    super(options.userMessage)
    this.name = 'ApiError'
    this.code = options.code
    this.status = options.status
    this.userMessage = options.userMessage
    this.traceId = options.traceId?.trim() || null
    this.originalError = options.cause
    this.data = options.data ?? null
  }
}

function isApiResponse(value: unknown): value is ApiResponse<unknown> {
  if (typeof value !== 'object' || value === null) {
    return false
  }

  const candidate = value as Partial<ApiResponse<unknown>>
  return typeof candidate.code === 'string' && typeof candidate.message === 'string'
}

export function normalizeApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }

  if (!axios.isAxiosError(error)) {
    return new ApiError({
      code: 'CLIENT_UNEXPECTED_ERROR',
      status: 0,
      userMessage: '页面处理失败，请重试。',
      cause: error,
    })
  }

  const body = isApiResponse(error.response?.data) ? error.response.data : null
  const responseHeader = error.response?.headers?.['x-trace-id']
  const traceId =
    body?.traceId || (typeof responseHeader === 'string' ? responseHeader : undefined)
  const status = error.response?.status ?? 0
  const code = body?.code ?? (status === 0 ? 'CLIENT_NETWORK_ERROR' : 'CLIENT_HTTP_ERROR')
  const userMessage =
    USER_MESSAGES[code] ??
    (status === 0 ? '无法连接到服务，请确认 TideBid 已启动。' : '请求未完成，请稍后重试。')

  return new ApiError({
    code,
    status,
    userMessage,
    traceId,
    cause: error,
    data: body?.data,
  })
}
