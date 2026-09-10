export const USERNAME_PATTERN = /^[A-Za-z0-9_]+$/

export function usernameValidationMessage(value: string): string | null {
  if (value.length === 0) {
    return '请输入用户名'
  }
  if (value.length < 4 || value.length > 32) {
    return '用户名长度应为 4～32 位'
  }
  if (!USERNAME_PATTERN.test(value)) {
    return '用户名只能包含字母、数字和下划线'
  }
  return null
}

export function nicknameValidationMessage(value: string): string | null {
  const normalized = value.trim()
  if (normalized.length === 0) {
    return '请输入昵称'
  }
  if (normalized.length > 64) {
    return '昵称不能超过 64 位'
  }
  return null
}

export function passwordValidationMessage(value: string): string | null {
  if (value.length === 0) {
    return '请输入密码'
  }
  if (value.length < 8 || value.length > 64) {
    return '密码长度应为 8～64 位'
  }
  if (new TextEncoder().encode(value).length > 72) {
    return '密码的 UTF-8 编码不能超过 72 字节'
  }
  return null
}
