import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import { ApiError } from '@/api/errors'
import { saveAuthSession } from '@/features/auth/session'
import { createAppRouter } from '@/router'
import OrderDetailView from '@/views/OrderDetailView.vue'

const accountApiMocks = vi.hoisted(() => ({
  getCurrentAccount: vi.fn<() => Promise<unknown>>(),
  getCurrentWallet: vi.fn<() => Promise<unknown>>(),
  loginAccount: vi.fn<() => Promise<unknown>>(),
  registerAccount: vi.fn<() => Promise<unknown>>(),
}))
const tradeApiMocks = vi.hoisted(() => ({
  getMyOrders: vi.fn<() => Promise<unknown>>(),
  getMySales: vi.fn<() => Promise<unknown>>(),
  getOrder: vi.fn<(orderId: string) => Promise<unknown>>(),
  payOrder: vi.fn<(orderId: string, requestId: string) => Promise<unknown>>(),
}))

vi.mock('@/api/account', () => accountApiMocks)
vi.mock('@/api/trade', () => tradeApiMocks)

const baseOrder = {
  orderId: '9007199254740993',
  orderNo: 'ORDER:9007199254740993',
  auctionId: '9007199254740994',
  itemId: '9007199254740995',
  sellerId: '8000000000000001',
  buyerId: '7000000000000002',
  itemTitle: '测试拍品',
  finalPrice: '1234.50',
  capturedDepositAmount: '200.00',
  payableAmount: '1034.50',
  status: 'PENDING_PAYMENT',
  paymentDeadline: '2026-09-19T03:00:00Z',
  paidAt: null,
  timedOutAt: null,
  sellerSettlementStatus: 'NOT_REQUIRED',
  sellerReceivableAmount: null,
  sellerCreditedAt: null,
  auctionClosedAt: '2026-09-19T02:00:00Z',
  createdAt: '2026-09-19T02:00:00Z',
  updatedAt: '2026-09-19T02:00:00Z',
  paymentEligible: true,
}

async function mountPage() {
  saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
  const router = createAppRouter(createMemoryHistory())
  await router.push(`/orders/${baseOrder.orderId}`)
  await router.isReady()
  const wrapper = mount(OrderDetailView, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  return wrapper
}

describe('order detail page', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    window.sessionStorage.clear()
    Object.values(accountApiMocks).forEach((mock) => mock.mockReset())
    Object.values(tradeApiMocks).forEach((mock) => mock.mockReset())
    accountApiMocks.getCurrentAccount.mockResolvedValue({
      traceId: 'trace-account',
      data: { userId: baseOrder.buyerId, username: 'buyer', nickname: '买家', roles: ['USER'] },
    })
    tradeApiMocks.getOrder.mockResolvedValue({ traceId: 'trace-order', data: baseOrder })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows exact money and Shanghai time and only exposes payment in an eligible state', async () => {
    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('¥1,234.50')
    expect(wrapper.text()).toContain('¥1,034.50')
    expect(wrapper.text()).toContain('2026/09/19 10:00')
    expect(wrapper.find('[data-test="pay-order"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('shows a stable insufficient-balance explanation and permits a later retry', async () => {
    tradeApiMocks.payOrder.mockResolvedValue({
      traceId: 'trace-pay',
      data: {
        paymentAttemptId: '9007199254740996',
        paymentNo: 'PAY:9007199254740996',
        orderId: baseOrder.orderId,
        amount: '1034.50',
        status: 'REJECTED',
        failureCode: 'ACCOUNT_WALLET_INSUFFICIENT_BALANCE',
        nextRecoveryAt: null,
        completedAt: '2026-09-19T02:01:00Z',
        createdAt: '2026-09-19T02:01:00Z',
        updatedAt: '2026-09-19T02:01:00Z',
      },
    })
    const wrapper = await mountPage()

    await wrapper.get('[data-test="pay-order"]').trigger('click')
    await flushPromises()

    expect(tradeApiMocks.payOrder).toHaveBeenCalledWith(
      baseOrder.orderId,
      expect.stringMatching(/^web-/),
    )
    expect(wrapper.text()).toContain('钱包可用余额不足')
    expect(wrapper.find('[data-test="pay-order"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('reuses the payment request id when a network result is unknown', async () => {
    tradeApiMocks.payOrder
      .mockRejectedValueOnce(new ApiError({
        code: 'CLIENT_NETWORK_ERROR', status: 0, userMessage: '网络未知',
      }))
      .mockResolvedValueOnce({
        traceId: 'trace-pay-recovered',
        data: {
          paymentAttemptId: '9007199254740996', paymentNo: 'PAY:9007199254740996',
          orderId: baseOrder.orderId, amount: '1034.50', status: 'SUCCEEDED', failureCode: null,
          nextRecoveryAt: null, completedAt: '2026-09-19T02:01:00Z',
          createdAt: '2026-09-19T02:01:00Z', updatedAt: '2026-09-19T02:01:00Z',
        },
      })
    const wrapper = await mountPage()

    await wrapper.get('[data-test="pay-order"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="pay-order"]').trigger('click')
    await flushPromises()

    expect(tradeApiMocks.payOrder).toHaveBeenCalledTimes(2)
    expect(tradeApiMocks.payOrder.mock.calls[0]![1]).toBe(tradeApiMocks.payOrder.mock.calls[1]![1])
    wrapper.unmount()
  })

  it('stops automatically polling an unknown payment after five rounds', async () => {
    tradeApiMocks.getOrder.mockResolvedValue({
      traceId: 'trace-processing',
      data: { ...baseOrder, status: 'PAYMENT_PROCESSING', paymentEligible: false },
    })
    const wrapper = await mountPage()

    for (let round = 0; round < 5; round += 1) {
      await vi.advanceTimersByTimeAsync(2_000)
      await flushPromises()
    }

    expect(tradeApiMocks.getOrder).toHaveBeenCalledTimes(6)
    expect(wrapper.text()).toContain('自动查询已停止')
    expect(wrapper.text()).toContain('不会被当作成功或失败')

    await vi.advanceTimersByTimeAsync(10_000)
    expect(tradeApiMocks.getOrder).toHaveBeenCalledTimes(6)
    wrapper.unmount()
  })
})
