import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import { ApiError } from '@/api/errors'
import { saveAuthSession } from '@/features/auth/session'
import { createAppRouter } from '@/router'
import AuctionDetailView from '@/views/AuctionDetailView.vue'

const auctionApiMocks = vi.hoisted(() => ({
  getAuctionDetail: vi.fn<(auctionId: string) => Promise<unknown>>(),
  getAuctionBidHistory: vi.fn<
    (auctionId: string, page?: number, size?: number) => Promise<unknown>
  >(),
  getAuctionRegistration: vi.fn<(registrationId: string) => Promise<unknown>>(),
  registerForAuction: vi.fn<(auctionId: string) => Promise<unknown>>(),
  placeAuctionBid: vi.fn<(auctionId: string, amount: string, requestId: string) => Promise<unknown>>(),
}))
const accountApiMocks = vi.hoisted(() => ({
  getCurrentAccount: vi.fn<() => Promise<unknown>>(),
  getCurrentWallet: vi.fn<() => Promise<unknown>>(),
  loginAccount: vi.fn<() => Promise<unknown>>(),
  registerAccount: vi.fn<() => Promise<unknown>>(),
}))

vi.mock('@/api/auction', () => auctionApiMocks)
vi.mock('@/api/account', () => accountApiMocks)

describe('auction detail', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    auctionApiMocks.getAuctionDetail.mockReset()
    auctionApiMocks.getAuctionBidHistory.mockReset()
    auctionApiMocks.getAuctionRegistration.mockReset()
    auctionApiMocks.registerForAuction.mockReset()
    auctionApiMocks.placeAuctionBid.mockReset()
    Object.values(accountApiMocks).forEach((mock) => mock.mockReset())
    accountApiMocks.getCurrentAccount.mockResolvedValue({
      traceId: 'trace-account',
      data: { userId: '7', username: 'buyer', nickname: '买家', roles: ['USER'] },
    })
    accountApiMocks.getCurrentWallet.mockResolvedValue({
      traceId: 'trace-wallet',
      data: { userId: '7', availableBalance: '9900.00', frozenBalance: '100.00' },
    })
  })

  it('shows immutable auction facts, registration and anonymized bid history', async () => {
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
    auctionApiMocks.getAuctionDetail.mockResolvedValue({
      traceId: 'trace-detail',
      data: {
        itemId: '2100000000000000001',
        auctionId: '2100000000000000002',
        title: '签名摄影作品',
        description: '艺术家签名版，保存状态良好。',
        category: '艺术品',
        itemCondition: 'GOOD',
        sessionStatus: 'OPEN',
        startPrice: '800.00',
        bidIncrement: '20.00',
        depositAmount: '100.00',
        currentPrice: '920.00',
        displayPrice: '920.00',
        minimumNextBid: '940.00',
        bidCount: 2,
        startAt: '2026-09-14T02:30:00Z',
        endAt: '2026-09-14T04:30:00Z',
        ownedByCurrentUser: false,
        images: [],
        myRegistration: {
          registrationId: '2100000000000000003',
          status: 'REGISTERED',
          failureCode: null,
          registeredAt: '2026-09-14T01:30:00Z',
        },
      },
    })
    auctionApiMocks.getAuctionBidHistory.mockResolvedValue({
      traceId: 'trace-bids',
      data: {
        auctionId: '2100000000000000002',
        page: 1,
        size: 10,
        total: 2,
        totalPages: 1,
        items: [
          {
            bidId: '2100000000000000005',
            amount: '920.00',
            previousPrice: '900.00',
            sequenceNo: 2,
            createdAt: '2026-09-14T02:45:00Z',
            mine: true,
          },
        ],
      },
    })
    const pinia = createPinia()
    const router = createAppRouter(createMemoryHistory())
    await router.push('/auctions/2100000000000000002')
    await router.isReady()

    const wrapper = mount(AuctionDetailView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('签名摄影作品')
    expect(wrapper.text()).toContain('¥920.00')
    expect(wrapper.text()).toContain('下一笔最低 ¥940.00')
    expect(wrapper.text()).toContain('报名状态：已报名')
    expect(wrapper.text()).toContain('我的报价')
    expect(wrapper.text()).toContain('trace-bids')
    expect(auctionApiMocks.getAuctionDetail).toHaveBeenCalledWith('2100000000000000002')
  })

  it('registers for a scheduled auction and refreshes the wallet and detail', async () => {
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
    const scheduled = {
      itemId: '31', auctionId: '32', title: '待报名拍品', description: '用于报名流程测试的拍品描述。',
      category: 'ART', itemCondition: 'GOOD', sessionStatus: 'SCHEDULED', startPrice: '100.00',
      bidIncrement: '10.00', depositAmount: '50.00', currentPrice: null, displayPrice: '100.00',
      minimumNextBid: '100.00', bidCount: 0, startAt: '2099-09-15T02:30:00Z',
      endAt: '2099-09-15T04:30:00Z', ownedByCurrentUser: false, images: [], myRegistration: null,
    }
    auctionApiMocks.getAuctionDetail
      .mockResolvedValueOnce({ traceId: 'trace-detail', data: scheduled })
      .mockResolvedValue({
        traceId: 'trace-detail-registered',
        data: {
          ...scheduled,
          myRegistration: { registrationId: '33', status: 'REGISTERED', failureCode: null, registeredAt: '2026-09-14T02:00:00Z' },
        },
      })
    auctionApiMocks.getAuctionBidHistory.mockResolvedValue({
      traceId: 'trace-bids', data: { auctionId: '32', page: 1, size: 10, total: 0, totalPages: 0, items: [] },
    })
    auctionApiMocks.registerForAuction.mockResolvedValue({
      traceId: 'trace-register',
      data: { registrationId: '33', auctionId: '32', depositAmount: '50.00', status: 'REGISTERED', failureCode: null, registeredAt: '2026-09-14T02:00:00Z', createdAt: '2026-09-14T02:00:00Z', updatedAt: '2026-09-14T02:00:00Z' },
    })
    const pinia = createPinia()
    const router = createAppRouter(createMemoryHistory())
    await router.push('/auctions/32')
    await router.isReady()
    const wrapper = mount(AuctionDetailView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    await wrapper.get('[data-test="register-auction"]').trigger('click')
    await flushPromises()

    expect(auctionApiMocks.registerForAuction).toHaveBeenCalledWith('32')
    expect(accountApiMocks.getCurrentWallet).toHaveBeenCalledOnce()
    expect(auctionApiMocks.getAuctionDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已报名')
    wrapper.unmount()
  })

  it('submits a manual bid with a stable request id and applies a conflict snapshot', async () => {
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
    auctionApiMocks.getAuctionDetail.mockResolvedValue({
      traceId: 'trace-detail',
      data: {
        itemId: '41', auctionId: '42', title: '竞价中拍品', description: '用于并发报价冲突测试的拍品。',
        category: 'ART', itemCondition: 'GOOD', sessionStatus: 'OPEN', startPrice: '100.00',
        bidIncrement: '10.00', depositAmount: '50.00', currentPrice: '110.00', displayPrice: '110.00',
        minimumNextBid: '120.00', bidCount: 1, startAt: '2026-09-14T01:00:00Z',
        endAt: '2099-09-15T04:30:00Z', ownedByCurrentUser: false, images: [],
        myRegistration: { registrationId: '43', status: 'REGISTERED', failureCode: null, registeredAt: '2026-09-14T00:30:00Z' },
      },
    })
    auctionApiMocks.getAuctionBidHistory.mockResolvedValue({
      traceId: 'trace-bids', data: { auctionId: '42', page: 1, size: 10, total: 0, totalPages: 0, items: [] },
    })
    auctionApiMocks.placeAuctionBid.mockRejectedValue(new ApiError({
      code: 'AUCTION_BID_CONFLICT', status: 409, userMessage: '价格变化', traceId: 'trace-conflict',
      data: { auctionId: '42', currentPrice: '120.00', minimumNextBid: '130.00', bidCount: 2, version: 7, status: 'OPEN' },
    }))
    const pinia = createPinia()
    const router = createAppRouter(createMemoryHistory())
    await router.push('/auctions/42')
    await router.isReady()
    const wrapper = mount(AuctionDetailView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    await wrapper.get('#bid-amount').setValue('120.00')
    await wrapper.get('[data-test="place-bid"]').trigger('click')
    await flushPromises()

    const call = auctionApiMocks.placeAuctionBid.mock.calls[0]!
    expect(call[0]).toBe('42')
    expect(call[1]).toBe('120.00')
    expect(call[2]).toMatch(/^web-/)
    expect(wrapper.get<HTMLInputElement>('#bid-amount').element.value).toBe('130.00')
    expect(wrapper.text()).toContain('下一笔最低 ¥130.00')
    expect(wrapper.text()).toContain('价格变化')
    wrapper.unmount()
  })
})
