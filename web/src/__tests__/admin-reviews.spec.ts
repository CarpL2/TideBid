import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import { saveAuthSession } from '@/features/auth/session'
import { createAppRouter } from '@/router'
import AdminReviewsView from '@/views/AdminReviewsView.vue'

const accountApiMocks = vi.hoisted(() => ({
  getCurrentAccount: vi.fn<() => Promise<unknown>>(),
  getCurrentWallet: vi.fn<() => Promise<unknown>>(),
  loginAccount: vi.fn<() => Promise<unknown>>(),
  registerAccount: vi.fn<() => Promise<unknown>>(),
}))
const auctionApiMocks = vi.hoisted(() => ({
  getPendingAuctionAssets: vi.fn<(page?: number, size?: number) => Promise<unknown>>(),
  getAuctionAsset: vi.fn<(assetId: string) => Promise<unknown>>(),
  reviewAuctionAsset: vi.fn<(assetId: string, input: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/account', () => accountApiMocks)
vi.mock('@/api/auction', () => auctionApiMocks)

const summary = {
  itemId: '9007199254740993',
  auctionId: '9007199254740994',
  sellerId: '88',
  title: '待审核机械键盘',
  category: 'ELECTRONICS',
  itemCondition: 'GOOD',
  reviewStatus: 'PENDING_REVIEW',
  submissionVersion: 3,
  startPrice: '100.00',
  bidIncrement: '10.00',
  depositAmount: '50.00',
  startAt: '2026-09-15T02:00:00Z',
  endAt: '2026-09-15T04:00:00Z',
  itemVersion: 7,
  sessionVersion: 2,
  submittedAt: '2026-09-14T02:00:00Z',
  coverImage: null,
}

const detail = {
  ...summary,
  description: '这是一把等待管理员审核的机械键盘。',
  sessionStatus: 'DRAFT',
  currentPrice: null,
  bidCount: 0,
  approvedAt: null,
  createdAt: '2026-09-14T01:00:00Z',
  updatedAt: '2026-09-14T02:00:00Z',
  images: [],
  latestReview: null,
}

function pendingPage(items = [summary]) {
  return { traceId: 'trace-pending', data: { page: 1, size: 12, total: items.length, totalPages: 1, items } }
}

async function mountPage(roles: string[]) {
  saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
  accountApiMocks.getCurrentAccount.mockResolvedValue({
    traceId: 'trace-account',
    data: { userId: '7', username: 'reviewer', nickname: '审核员', roles },
  })
  const pinia = createPinia()
  const router = createAppRouter(createMemoryHistory())
  await router.push('/admin/reviews')
  await router.isReady()
  const wrapper = mount(AdminReviewsView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return wrapper
}

describe('admin review console', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    Object.values(accountApiMocks).forEach((mock) => mock.mockReset())
    Object.values(auctionApiMocks).forEach((mock) => mock.mockReset())
    auctionApiMocks.getPendingAuctionAssets.mockResolvedValue(pendingPage())
    auctionApiMocks.getAuctionAsset.mockResolvedValue({ traceId: 'trace-detail', data: detail })
    auctionApiMocks.reviewAuctionAsset.mockResolvedValue({ traceId: 'trace-review', data: {} })
  })

  it('loads the exact submission and approves its current version', async () => {
    const wrapper = await mountPage(['USER', 'ADMIN'])

    expect(wrapper.text()).toContain('待审核机械键盘')
    await wrapper.get('[data-test="open-review"]').trigger('click')
    await flushPromises()
    expect(auctionApiMocks.getAuctionAsset).toHaveBeenCalledWith('9007199254740993')
    expect(wrapper.text()).toContain('这是一把等待管理员审核的机械键盘。')

    await wrapper.get('[data-test="approve-review"]').trigger('click')
    await flushPromises()
    expect(auctionApiMocks.reviewAuctionAsset).toHaveBeenCalledWith('9007199254740993', {
      decision: 'APPROVE',
      submissionVersion: 3,
      comment: null,
    })
  })

  it('requires an opinion before rejecting', async () => {
    const wrapper = await mountPage(['ADMIN'])
    await wrapper.get('[data-test="open-review"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test="reject-review"]').trigger('click')
    expect(wrapper.text()).toContain('驳回时必须填写审核意见')
    expect(auctionApiMocks.reviewAuctionAsset).not.toHaveBeenCalled()

    await wrapper.get('textarea').setValue('图片信息不足，请补充细节。')
    await wrapper.get('[data-test="reject-review"]').trigger('click')
    await flushPromises()
    expect(auctionApiMocks.reviewAuctionAsset).toHaveBeenCalledWith('9007199254740993', {
      decision: 'REJECT',
      submissionVersion: 3,
      comment: '图片信息不足，请补充细节。',
    })
  })

  it('does not expose the queue or review actions to a regular user', async () => {
    const wrapper = await mountPage(['USER'])

    expect(wrapper.get('[data-test="admin-denied"]')).toBeTruthy()
    expect(wrapper.text()).toContain('当前账号没有审核权限')
    expect(wrapper.find('[data-test="open-review"]').exists()).toBe(false)
    expect(auctionApiMocks.getPendingAuctionAssets).not.toHaveBeenCalled()
  })
})
