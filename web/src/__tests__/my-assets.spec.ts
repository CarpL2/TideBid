import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import { saveAuthSession } from '@/features/auth/session'
import { createAppRouter } from '@/router'
import MyAssetsView from '@/views/MyAssetsView.vue'

const auctionApiMocks = vi.hoisted(() => ({
  getMyAuctionAssets: vi.fn<(page?: number, size?: number) => Promise<unknown>>(),
  submitAuctionAsset: vi.fn<
    (assetId: string, itemVersion: number, sessionVersion: number) => Promise<unknown>
  >(),
}))

vi.mock('@/api/auction', () => auctionApiMocks)

describe('seller asset list', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    auctionApiMocks.getMyAuctionAssets.mockReset()
    auctionApiMocks.submitAuctionAsset.mockReset()
  })

  it('shows editable drafts and submits the exact optimistic-lock versions', async () => {
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
    auctionApiMocks.getMyAuctionAssets.mockResolvedValue({
      traceId: 'trace-assets',
      data: {
        page: 1,
        size: 12,
        total: 2,
        totalPages: 1,
        items: [
          {
            itemId: '9007199254740993',
            auctionId: '9007199254740994',
            title: '机械键盘',
            category: 'ELECTRONICS',
            itemCondition: 'GOOD',
            reviewStatus: 'DRAFT',
            sessionStatus: 'DRAFT',
            startPrice: '100.00',
            currentPrice: null,
            startAt: '2026-09-15T02:00:00Z',
            endAt: '2026-09-15T04:00:00Z',
            itemVersion: 3,
            sessionVersion: 5,
            coverImage: null,
            createdAt: '2026-09-14T02:00:00Z',
            updatedAt: '2026-09-14T02:00:00Z',
          },
          {
            itemId: '9007199254740995', auctionId: '9007199254740996', title: '已成交藏品',
            category: 'COLLECTIBLES', itemCondition: 'LIKE_NEW', reviewStatus: 'APPROVED',
            sessionStatus: 'CLOSED_SOLD', startPrice: '500.00', currentPrice: '880.00',
            startAt: '2026-09-13T02:00:00Z', endAt: '2026-09-13T04:00:00Z',
            itemVersion: 2, sessionVersion: 8, coverImage: null,
            createdAt: '2026-09-12T02:00:00Z', updatedAt: '2026-09-13T04:00:01Z',
          },
        ],
      },
    })
    auctionApiMocks.submitAuctionAsset.mockResolvedValue({ traceId: 'trace-submit', data: {} })
    const pinia = createPinia()
    const router = createAppRouter(createMemoryHistory())
    await router.push('/assets/mine')
    await router.isReady()
    const wrapper = mount(MyAssetsView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('机械键盘')
    expect(wrapper.text()).toContain('草稿')
    expect(wrapper.text()).toContain('已成交')
    expect(wrapper.text()).toContain('¥880.00')
    expect(wrapper.get('a[href="/assets/9007199254740993/edit"]')).toBeTruthy()
    await wrapper.get('.seller-asset-row__actions button').trigger('click')
    await flushPromises()
    expect(auctionApiMocks.submitAuctionAsset).toHaveBeenCalledWith('9007199254740993', 3, 5)
  })
})
