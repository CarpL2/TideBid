import { describe, expect, it } from 'vitest'

import {
  maskUserId,
  orderStatusLabel,
  paymentFailureLabel,
  settlementStatusLabel,
} from '@/features/trade/presentation'

describe('trade presentation', () => {
  it('maps order and settlement states without claiming unknown payments succeeded', () => {
    expect(orderStatusLabel('PAYMENT_PROCESSING')).toBe('支付结果确认中')
    expect(orderStatusLabel('PAID')).toBe('已支付')
    expect(settlementStatusLabel('PENDING')).toBe('卖家入账中')
  })

  it('explains insufficient balance and masks buyer identifiers for sellers', () => {
    expect(paymentFailureLabel('ACCOUNT_WALLET_INSUFFICIENT_BALANCE')).toContain('余额不足')
    expect(maskUserId('9007199254740993')).toBe('90***93')
  })
})
