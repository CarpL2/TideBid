import { describe, expect, it } from 'vitest'

import {
  conditionLabel,
  formatMoney,
  formatShanghaiTime,
  registrationStatusLabel,
  sessionStatusLabel,
} from '@/features/auction/presentation'

describe('auction presentation', () => {
  it('formats DECIMAL values as strings without losing large integer precision', () => {
    expect(formatMoney('9007199254740993.20')).toBe('¥9,007,199,254,740,993.20')
    expect(formatMoney('000125.5')).toBe('¥125.50')
    expect(formatMoney(null)).toBe('--')
  })

  it('always presents backend instants in Asia/Shanghai', () => {
    expect(formatShanghaiTime('2026-09-14T02:30:00Z')).toContain('2026/09/14 10:30')
    expect(formatShanghaiTime('not-an-instant')).toBe('--')
  })

  it('maps domain enums to stable Chinese labels', () => {
    expect(sessionStatusLabel('OPEN')).toBe('竞价中')
    expect(conditionLabel('LIKE_NEW')).toBe('几乎全新')
    expect(registrationStatusLabel('PENDING_HOLD')).toBe('保证金处理中')
  })
})
