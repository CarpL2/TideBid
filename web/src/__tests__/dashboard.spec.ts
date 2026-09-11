import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import { saveAuthSession } from '@/features/auth/session'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import DashboardView from '@/views/DashboardView.vue'

describe('account dashboard', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('shows the signed-in identity, wallet and disabled future navigation', async () => {
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
    const pinia = createPinia()
    const router = createAppRouter(createMemoryHistory())
    await router.push('/dashboard')
    await router.isReady()
    const authStore = useAuthStore(pinia)
    authStore.$patch({
      profile: { userId: '2098215937757904897', username: 'carp_bidder', nickname: '鲤鱼', roles: ['USER'] },
      wallet: { userId: '2098215937757904897', availableBalance: '10000.00', frozenBalance: '0.00' },
      lastTraceId: 'trace-wallet',
    })
    vi.spyOn(authStore, 'loadDashboard').mockResolvedValue()

    const wrapper = mount(DashboardView, {
      global: { plugins: [pinia, router] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('鲤鱼')
    expect(wrapper.text()).toContain('@carp_bidder')
    expect(wrapper.text()).toContain('2098215937757904897')
    expect(wrapper.text()).toContain('10,000.00')
    expect(wrapper.text()).toContain('trace-wallet')
    expect(wrapper.findAll('nav button').every((button) => button.attributes('disabled') !== undefined))
      .toBe(true)
  })
})
