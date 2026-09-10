import { requestData } from '@/api/http'
import type {
  AccessToken,
  ApiResult,
  CurrentAccount,
  CurrentWallet,
  LoginInput,
  RegisterAccountInput,
  RegisteredAccount,
} from '@/types/api'

export function registerAccount(
  input: RegisterAccountInput,
): Promise<ApiResult<RegisteredAccount>> {
  return requestData<RegisteredAccount>({
    method: 'POST',
    url: '/auth/register',
    data: input,
  })
}

export function loginAccount(input: LoginInput): Promise<ApiResult<AccessToken>> {
  return requestData<AccessToken>({
    method: 'POST',
    url: '/auth/login',
    data: input,
  })
}

export function getCurrentAccount(): Promise<ApiResult<CurrentAccount>> {
  return requestData<CurrentAccount>({
    method: 'GET',
    url: '/users/me',
  })
}

export function getCurrentWallet(): Promise<ApiResult<CurrentWallet>> {
  return requestData<CurrentWallet>({
    method: 'GET',
    url: '/wallets/me',
  })
}
