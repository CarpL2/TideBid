import { requestData } from '@/api/http'
import type { ApiResult } from '@/types/api'
import type { TradeOrder, TradeOrderPage, TradePaymentAttempt } from '@/types/trade'

export function getMyOrders(page = 1, size = 12): Promise<ApiResult<TradeOrderPage>> {
  return requestData<TradeOrderPage>({
    method: 'GET',
    url: '/orders/mine',
    params: { page, size },
  })
}

export function getMySales(page = 1, size = 12): Promise<ApiResult<TradeOrderPage>> {
  return requestData<TradeOrderPage>({
    method: 'GET',
    url: '/orders/sales',
    params: { page, size },
  })
}

export function getOrder(orderId: string): Promise<ApiResult<TradeOrder>> {
  return requestData<TradeOrder>({
    method: 'GET',
    url: `/orders/${encodeURIComponent(orderId)}`,
  })
}

export function payOrder(
  orderId: string,
  requestId: string,
): Promise<ApiResult<TradePaymentAttempt>> {
  return requestData<TradePaymentAttempt>({
    method: 'POST',
    url: `/orders/${encodeURIComponent(orderId)}/pay`,
    headers: { 'X-Request-Id': requestId },
  })
}
