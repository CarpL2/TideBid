import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { readAuthSession } from '@/features/auth/session'
import { useAuthStore } from '@/stores/auth'

type AccountApi = typeof import('@/api/account')

const accountApi = vi.hoisted(() => ({
  getCurrentAccount: vi.fn<AccountApi['getCurrentAccount']>(),
  getCurrentWallet: vi.fn<AccountApi['getCurrentWallet']>(),
  loginAccount: vi.fn<AccountApi['loginAccount']>(),
  registerAccount: vi.fn<AccountApi['registerAccount']>(),
}))

vi.mock('@/api/account', () => accountApi)

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    window.sessionStorage.clear()
    vi.clearAllMocks()
  })

  it('persists login and joins profile with the matching wallet', async () => {
    accountApi.loginAccount.mockResolvedValue({
      data: { accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 7_200 },
      traceId: 'trace-login',
    })
    accountApi.getCurrentAccount.mockResolvedValue({
      data: { userId: '7', username: 'carp_bidder', nickname: '鲤鱼', roles: ['USER'] },
      traceId: 'trace-profile',
    })
    accountApi.getCurrentWallet.mockResolvedValue({
      data: { userId: '7', availableBalance: '10000.00', frozenBalance: '0.00' },
      traceId: 'trace-wallet',
    })
    const store = useAuthStore()

    await store.login({ username: 'carp_bidder', password: 'ChangeMe-123' })
    await store.loadDashboard()

    expect(readAuthSession()?.accessToken).toBe('signed-token')
    expect(store.isAuthenticated).toBe(true)
    expect(store.profile?.nickname).toBe('鲤鱼')
    expect(store.wallet?.availableBalance).toBe('10000.00')
    expect(store.lastTraceId).toBe('trace-wallet')
  })

  it('rejects account and wallet data that belong to different users', async () => {
    accountApi.getCurrentAccount.mockResolvedValue({
      data: { userId: '7', username: 'carp_bidder', nickname: '鲤鱼', roles: ['USER'] },
      traceId: 'trace-profile',
    })
    accountApi.getCurrentWallet.mockResolvedValue({
      data: { userId: '8', availableBalance: '10000.00', frozenBalance: '0.00' },
      traceId: 'trace-wallet',
    })
    const store = useAuthStore()

    await expect(store.loadDashboard()).rejects.toMatchObject({
      code: 'CLIENT_INCONSISTENT_ACCOUNT',
    })
    expect(store.dashboardLoading).toBe(false)
  })

  it('clears identity and the browser session on logout', async () => {
    accountApi.loginAccount.mockResolvedValue({
      data: { accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 },
      traceId: null,
    })
    const store = useAuthStore()
    await store.login({ username: 'carp_bidder', password: 'ChangeMe-123' })

    store.logout()

    expect(store.session).toBeNull()
    expect(readAuthSession()).toBeNull()
  })
})
