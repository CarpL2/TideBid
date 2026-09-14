import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import { saveAuthSession } from '@/features/auth/session'
import { createAppRouter } from '@/router'
import AuctionDetailView from '@/views/AuctionDetailView.vue'

const auctionApiMocks = vi.hoisted(() => ({
  getAuctionDetail: vi.fn<(auctionId: string) => Promise<unknown>>(),
  getAuctionBidHistory: vi.fn<
    (auctionId: string, page?: number, size?: number) => Promise<unknown>
  >(),
}))

vi.mock('@/api/auction', () => auctionApiMocks)

describe('auction detail', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    auctionApiMocks.getAuctionDetail.mockReset()
    auctionApiMocks.getAuctionBidHistory.mockReset()
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
})
