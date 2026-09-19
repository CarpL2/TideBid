import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import { saveAuthSession } from '@/features/auth/session'
import { createAppRouter } from '@/router'
import AuctionLobbyView from '@/views/AuctionLobbyView.vue'

const getAuctionLobbyMock = vi.hoisted(() =>
  vi.fn<(page?: number, size?: number) => Promise<unknown>>(),
)

vi.mock('@/api/auction', () => ({
  getAuctionLobby: getAuctionLobbyMock,
}))

describe('auction lobby', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    getAuctionLobbyMock.mockReset()
  })

  it('renders backend prices, Shanghai time and exact string IDs', async () => {
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
    getAuctionLobbyMock.mockResolvedValue({
      traceId: 'trace-auction-page',
      data: {
        page: 1,
        size: 12,
        total: 1,
        totalPages: 1,
        items: [
          {
            itemId: '9007199254740993',
            auctionId: '9007199254740995',
            title: '限量机械腕表',
            category: '腕表',
            itemCondition: 'LIKE_NEW',
            sessionStatus: 'OPEN',
            startPrice: '1000.00',
            currentPrice: '1288.00',
            displayPrice: '1288.00',
            minimumNextBid: '1298.00',
            bidCount: 7,
            startAt: '2026-09-14T02:30:00Z',
            endAt: '2026-09-14T03:30:00Z',
            coverImage: null,
          },
        ],
      },
    })
    const pinia = createPinia()
    const router = createAppRouter(createMemoryHistory())
    await router.push('/auctions')
    await router.isReady()

    const wrapper = mount(AuctionLobbyView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('限量机械腕表')
    expect(wrapper.text()).toContain('¥1,288.00')
    expect(wrapper.text()).toContain('2026/09/14 10:30')
    expect(wrapper.text()).toContain('竞价中')
    expect(wrapper.text()).toContain('trace-auction-page')
    expect(wrapper.get('.auction-card').attributes('href')).toBe('/auctions/9007199254740995')
  })

  it('shows sold and unsold terminal outcomes instead of another bid prompt', async () => {
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
    const terminalBase = {
      itemId: '51', title: '终态拍品', category: 'ART', itemCondition: 'GOOD',
      startPrice: '100.00', currentPrice: '150.00', displayPrice: '150.00',
      minimumNextBid: '160.00', bidCount: 3, startAt: '2026-09-14T01:00:00Z',
      endAt: '2026-09-14T02:00:00Z', closedAt: '2026-09-14T02:00:01Z', coverImage: null,
    }
    getAuctionLobbyMock.mockResolvedValue({
      traceId: 'trace-terminal',
      data: {
        page: 1, size: 12, total: 2, totalPages: 1,
        items: [
          { ...terminalBase, auctionId: '52', sessionStatus: 'CLOSED_SOLD', finalPrice: '150.00' },
          { ...terminalBase, itemId: '53', auctionId: '54', title: '无人出价拍品', sessionStatus: 'CLOSED_UNSOLD', currentPrice: null, finalPrice: null, bidCount: 0 },
        ],
      },
    })
    const router = createAppRouter(createMemoryHistory())
    await router.push('/auctions')
    await router.isReady()
    const wrapper = mount(AuctionLobbyView, { global: { plugins: [createPinia(), router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('已成交')
    expect(wrapper.text()).toContain('¥150.00')
    expect(wrapper.text()).toContain('已流拍')
    expect(wrapper.text()).toContain('流拍')
  })
})
