import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import {
  getCurrentAccount,
  getCurrentWallet,
  loginAccount,
  registerAccount,
} from '@/api/account'
import { ApiError } from '@/api/errors'
import {
  clearAuthSession,
  readAuthSession,
  saveAuthSession,
  type AuthSession,
} from '@/features/auth/session'
import type {
  CurrentAccount,
  CurrentWallet,
  LoginInput,
  RegisterAccountInput,
  RegisteredAccount,
} from '@/types/api'

export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthSession | null>(readAuthSession())
  const profile = ref<CurrentAccount | null>(null)
  const profileLoading = ref(false)
  const wallet = ref<CurrentWallet | null>(null)
  const dashboardLoading = ref(false)
  const lastTraceId = ref<string | null>(null)

  const isAuthenticated = computed(
    () => session.value !== null && session.value.expiresAt > Date.now(),
  )
  let profileRequest: Promise<CurrentAccount> | null = null

  function restoreSession(): boolean {
    session.value = readAuthSession()
    return session.value !== null
  }

  function clearDashboard(): void {
    profile.value = null
    wallet.value = null
    lastTraceId.value = null
  }

  async function loadProfile(): Promise<CurrentAccount> {
    if (profile.value) {
      return profile.value
    }
    if (profileRequest) {
      return profileRequest
    }

    profileLoading.value = true
    profileRequest = getCurrentAccount()
      .then((result) => {
        profile.value = result.data
        lastTraceId.value = result.traceId
        return result.data
      })
      .finally(() => {
        profileLoading.value = false
        profileRequest = null
      })
    return profileRequest
  }

  function logout(): void {
    clearAuthSession()
    session.value = null
    clearDashboard()
  }

  async function login(input: LoginInput): Promise<void> {
    const result = await loginAccount(input)
    session.value = saveAuthSession(result.data)
    lastTraceId.value = result.traceId
  }

  async function register(input: RegisterAccountInput): Promise<RegisteredAccount> {
    const result = await registerAccount(input)
    lastTraceId.value = result.traceId
    return result.data
  }

  async function loadDashboard(): Promise<void> {
    dashboardLoading.value = true
    try {
      const [account, walletResult] = await Promise.all([loadProfile(), getCurrentWallet()])

      if (account.userId !== walletResult.data.userId) {
        throw new ApiError({
          code: 'CLIENT_INCONSISTENT_ACCOUNT',
          status: 0,
          userMessage: '账户资料与钱包数据不一致，请联系管理员。',
          traceId: walletResult.traceId ?? lastTraceId.value,
        })
      }

      wallet.value = walletResult.data
      lastTraceId.value = walletResult.traceId ?? lastTraceId.value
    } finally {
      dashboardLoading.value = false
    }
  }

  return {
    session,
    profile,
    profileLoading,
    wallet,
    dashboardLoading,
    lastTraceId,
    isAuthenticated,
    restoreSession,
    login,
    register,
    loadDashboard,
    loadProfile,
    logout,
  }
})
