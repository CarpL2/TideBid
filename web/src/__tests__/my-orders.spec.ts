import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import { saveAuthSession } from '@/features/auth/session'
import { createAppRouter } from '@/router'
import MyOrdersView from '@/views/MyOrdersView.vue'

const accountApiMocks = vi.hoisted(() => ({
  getCurrentAccount: vi.fn<() => Promise<unknown>>(),
  getCurrentWallet: vi.fn<() => Promise<unknown>>(),
  loginAccount: vi.fn<() => Promise<unknown>>(),
  registerAccount: vi.fn<() => Promise<unknown>>(),
}))
const tradeApiMocks = vi.hoisted(() => ({
  getMyOrders: vi.fn<(page?: number, size?: number) => Promise<unknown>>(),
  getMySales: vi.fn<(page?: number, size?: number) => Promise<unknown>>(),
  getOrder: vi.fn<() => Promise<unknown>>(),
  payOrder: vi.fn<() => Promise<unknown>>(),
}))

vi.mock('@/api/account', () => accountApiMocks)
vi.mock('@/api/trade', () => tradeApiMocks)

const order = {
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

describe('my orders page', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    Object.values(accountApiMocks).forEach((mock) => mock.mockReset())
    Object.values(tradeApiMocks).forEach((mock) => mock.mockReset())
    accountApiMocks.getCurrentAccount.mockResolvedValue({
      traceId: 'trace-account',
      data: { userId: order.buyerId, username: 'buyer', nickname: '买家', roles: ['USER'] },
    })
    tradeApiMocks.getMyOrders.mockResolvedValue({
      traceId: 'trace-buying',
      data: { page: 1, size: 12, total: 1, totalPages: 1, items: [order] },
    })
    tradeApiMocks.getMySales.mockResolvedValue({
      traceId: 'trace-sales',
      data: {
        page: 1,
        size: 12,
        total: 1,
        totalPages: 1,
        items: [{ ...order, sellerReceivableAmount: '1234.50', sellerSettlementStatus: 'PENDING' }],
      },
    })
  })

  it('keeps large identifiers and decimal amounts intact across buying and sales views', async () => {
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
    const router = createAppRouter(createMemoryHistory())
    await router.push('/orders')
    await router.isReady()
    const wrapper = mount(MyOrdersView, { global: { plugins: [createPinia(), router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('ORDER:9007199254740993')
    expect(wrapper.text()).toContain('¥1,234.50')
    expect(wrapper.get('a[href="/orders/9007199254740993"]')).toBeTruthy()

    await wrapper.findAll('[role="tab"]')[1]!.trigger('click')
    await flushPromises()

    expect(tradeApiMocks.getMySales).toHaveBeenCalledWith(1, 12)
    expect(wrapper.text()).toContain('买家 70***02')
    expect(wrapper.text()).toContain('卖家入账中')
  })
})
