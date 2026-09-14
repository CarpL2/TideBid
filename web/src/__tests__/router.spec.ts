import { createMemoryHistory } from 'vue-router'
import { beforeEach, describe, expect, it } from 'vitest'

import { saveAuthSession } from '@/features/auth/session'
import { createAppRouter } from '@/router'

describe('authentication route guard', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('sends an anonymous dashboard visitor to login with a safe return path', async () => {
    const router = createAppRouter(createMemoryHistory())

    await router.push('/dashboard')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/dashboard')
  })

  it('keeps an authenticated visitor out of guest-only pages', async () => {
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })
    const router = createAppRouter(createMemoryHistory())

    await router.push('/register')

    expect(router.currentRoute.value.name).toBe('dashboard')
  })

  it('protects the auction lobby and preserves its return path', async () => {
    const router = createAppRouter(createMemoryHistory())

    await router.push('/auctions/2098215937757904897')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/auctions/2098215937757904897')
  })
})
