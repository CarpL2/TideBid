import type { AccessToken } from '@/types/api'

export const AUTH_SESSION_KEY = 'tidebid.auth.session.v1'

export interface AuthSession {
  accessToken: string
  tokenType: 'Bearer'
  expiresAt: number
}

function sessionStorageOrNull(): Storage | null {
  if (typeof window === 'undefined') {
    return null
  }

  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

function isAuthSession(value: unknown, now: number): value is AuthSession {
  if (typeof value !== 'object' || value === null) {
    return false
  }

  const candidate = value as Partial<AuthSession>
  return (
    typeof candidate.accessToken === 'string' &&
    candidate.accessToken.length > 0 &&
    candidate.tokenType === 'Bearer' &&
    typeof candidate.expiresAt === 'number' &&
    Number.isFinite(candidate.expiresAt) &&
    candidate.expiresAt > now
  )
}

export function readAuthSession(now = Date.now()): AuthSession | null {
  const storage = sessionStorageOrNull()
  if (!storage) {
    return null
  }

  try {
    const rawSession = storage.getItem(AUTH_SESSION_KEY)
    if (!rawSession) {
      return null
    }

    const parsed: unknown = JSON.parse(rawSession)
    if (!isAuthSession(parsed, now)) {
      storage.removeItem(AUTH_SESSION_KEY)
      return null
    }

    return parsed
  } catch {
    storage.removeItem(AUTH_SESSION_KEY)
    return null
  }
}

export function saveAuthSession(token: AccessToken, now = Date.now()): AuthSession {
  if (token.tokenType !== 'Bearer' || token.accessToken.length === 0 || token.expiresIn <= 0) {
    throw new Error('Invalid access token response')
  }

  const session: AuthSession = {
    accessToken: token.accessToken,
    tokenType: token.tokenType,
    expiresAt: now + token.expiresIn * 1_000,
  }

  sessionStorageOrNull()?.setItem(AUTH_SESSION_KEY, JSON.stringify(session))
  return session
}

export function clearAuthSession(): void {
  sessionStorageOrNull()?.removeItem(AUTH_SESSION_KEY)
}

export function hasValidAuthSession(now = Date.now()): boolean {
  return readAuthSession(now) !== null
}
