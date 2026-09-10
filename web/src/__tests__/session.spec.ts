import { beforeEach, describe, expect, it } from 'vitest'

import {
  AUTH_SESSION_KEY,
  clearAuthSession,
  hasValidAuthSession,
  readAuthSession,
  saveAuthSession,
} from '@/features/auth/session'

describe('authentication session', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('stores a valid Bearer token only for the current browser tab', () => {
    const session = saveAuthSession(
      { accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 7_200 },
      1_000,
    )

    expect(session.expiresAt).toBe(7_201_000)
    expect(readAuthSession(2_000)).toEqual(session)
    expect(hasValidAuthSession(2_000)).toBe(true)
    expect(window.localStorage.getItem(AUTH_SESSION_KEY)).toBeNull()
  })

  it('removes expired or malformed sessions instead of trusting them', () => {
    saveAuthSession({ accessToken: 'expired', tokenType: 'Bearer', expiresIn: 1 }, 1_000)

    expect(readAuthSession(2_001)).toBeNull()
    expect(window.sessionStorage.getItem(AUTH_SESSION_KEY)).toBeNull()

    window.sessionStorage.setItem(AUTH_SESSION_KEY, '{not-json')
    expect(readAuthSession()).toBeNull()
    expect(window.sessionStorage.getItem(AUTH_SESSION_KEY)).toBeNull()
  })

  it('clears the session explicitly on logout', () => {
    saveAuthSession({ accessToken: 'signed-token', tokenType: 'Bearer', expiresIn: 60 })

    clearAuthSession()

    expect(readAuthSession()).toBeNull()
  })
})
