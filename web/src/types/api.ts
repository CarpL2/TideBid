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
  userId: string
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
  userId: string
  username: string
  nickname: string
  roles: string[]
}

export interface CurrentWallet {
  userId: string
  availableBalance: number | string
  frozenBalance: number | string
}
