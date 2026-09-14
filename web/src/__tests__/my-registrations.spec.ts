import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import { saveAuthSession } from '@/features/auth/session'
import { createAppRouter } from '@/router'
import MyRegistrationsView from '@/views/MyRegistrationsView.vue'

const accountApiMocks = vi.hoisted(() => ({
  getCurrentAccount: vi.fn<() => Promise<unknown>>(),
  getCurrentWallet: vi.fn<() => Promise<unknown>>(),
  loginAccount: vi.fn<() => Promise<unknown>>(),
  registerAccount: vi.fn<() => Promise<unknown>>(),
}))
const auctionApiMocks = vi.hoisted(() => ({
  getMyAuctionRegistrations: vi.fn<(page?: number, size?: number) => Promise<unknown>>(),
  getAuctionRegistration: vi.fn<(registrationId: string) => Promise<unknown>>(),
}))

vi.mock('@/api/account', () => accountApiMocks)
vi.mock('@/api/auction', () => auctionApiMocks)

const pendingRegistration = {
  registrationId: '9007199254740993',
  auctionId: '9007199254740994',
  depositAmount: '50.00',
  status: 'PENDING_HOLD',
  failureCode: null,
  registeredAt: null,
  createdAt: '2026-09-14T02:00:00Z',
  updatedAt: '2026-09-14T02:00:00Z',
}

describe('my registrations page', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    window.sessionStorage.clear()
    Object.values(accountApiMocks).forEach((mock) => mock.mockReset())
    Object.values(auctionApiMocks).forEach((mock) => mock.mockReset())
    accountApiMocks.getCurrentAccount.mockResolvedValue({
      traceId: 'trace-account',
      data: { userId: '7', username: 'buyer', nickname: '买家', roles: ['USER'] },
    })
    accountApiMocks.getCurrentWallet
      .mockResolvedValueOnce({
        traceId: 'trace-wallet-before',
        data: { userId: '7', availableBalance: '10000.00', frozenBalance: '0.00' },
      })
      .mockResolvedValue({
        traceId: 'trace-wallet-after',
        data: { userId: '7', availableBalance: '9950.00', frozenBalance: '50.00' },
      })
    auctionApiMocks.getMyAuctionRegistrations.mockResolvedValue({
      traceId: 'trace-list',
      data: { page: 1, size: 12, total: 1, totalPages: 1, items: [pendingRegistration] },
    })
    auctionApiMocks.getAuctionRegistration.mockResolvedValue({
      traceId: 'trace-final',
      data: {
        ...pendingRegistration,
        status: 'REGISTERED',
        registeredAt: '2026-09-14T02:00:02Z',
        updatedAt: '2026-09-14T02:00:02Z',
      },
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('polls a pending hold to its final state and refreshes wallet balances', async () => {
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
    const pinia = createPinia()
    const router = createAppRouter(createMemoryHistory())
    await router.push('/registrations/mine')
    await router.isReady()
    const wrapper = mount(MyRegistrationsView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('保证金处理中')
    expect(wrapper.get('a[href="/auctions/9007199254740994"]')).toBeTruthy()

    await vi.advanceTimersByTimeAsync(2_000)
    await flushPromises()

    expect(auctionApiMocks.getAuctionRegistration).toHaveBeenCalledWith('9007199254740993')
    expect(wrapper.text()).toContain('已报名')
    expect(wrapper.text()).toContain('¥9,950.00')
    expect(wrapper.text()).toContain('¥50.00')
    expect(accountApiMocks.getCurrentWallet).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('shows a stable explanation for a failed deposit', async () => {
    auctionApiMocks.getMyAuctionRegistrations.mockResolvedValue({
      traceId: 'trace-list',
      data: {
        page: 1,
        size: 12,
        total: 1,
        totalPages: 1,
        items: [
          {
            ...pendingRegistration,
            status: 'FAILED',
            failureCode: 'ACCOUNT_WALLET_INSUFFICIENT_BALANCE',
          },
        ],
      },
    })
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
    const pinia = createPinia()
    const router = createAppRouter(createMemoryHistory())
    await router.push('/registrations/mine')
    await router.isReady()
    const wrapper = mount(MyRegistrationsView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('报名失败')
    expect(wrapper.text()).toContain('可用余额不足')
    expect(auctionApiMocks.getAuctionRegistration).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
