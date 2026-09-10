import { describe, expect, it } from 'vitest'

import {
  nicknameValidationMessage,
  passwordValidationMessage,
  usernameValidationMessage,
} from '@/features/auth/validation'

describe('authentication form validation', () => {
  it('enforces the backend username boundary', () => {
    expect(usernameValidationMessage('carp_bidder')).toBeNull()
    expect(usernameValidationMessage('abc')).toContain('4～32')
    expect(usernameValidationMessage('鲤鱼用户')).toContain('字母、数字和下划线')
  })

  it('trims nicknames and checks their visible length', () => {
    expect(nicknameValidationMessage('  鲤鱼  ')).toBeNull()
    expect(nicknameValidationMessage('   ')).toBe('请输入昵称')
  })

  it('keeps BCrypt inputs below the 72-byte boundary', () => {
    expect(passwordValidationMessage('ChangeMe-123')).toBeNull()
    expect(passwordValidationMessage('short')).toContain('8～64')
    expect(passwordValidationMessage('界'.repeat(25))).toContain('72 字节')
  })
})
