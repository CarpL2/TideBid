export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  traceId: string
}

export interface ApiResult<T> {
  data: T
  traceId: string | null
}

export interface RegisterAccountInput {
  username: string
  nickname: string
  password: string
}

export interface RegisteredAccount {
  userId: number
  username: string
  nickname: string
  roles: string[]
}

export interface LoginInput {
  username: string
  password: string
}

export interface AccessToken {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
}

export interface CurrentAccount {
  userId: number
  username: string
  nickname: string
  roles: string[]
}

export interface CurrentWallet {
  userId: number
  availableBalance: number | string
  frozenBalance: number | string
}
