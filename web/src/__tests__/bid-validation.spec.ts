import { describe, expect, it } from 'vitest'

import { validateBidAmount } from '@/features/auction/bid-validation'

describe('bid amount validation', () => {
  it('compares decimal amounts as integer cents without floating point', () => {
    expect(validateBidAmount('900719925474099.99', '900719925474099.98')).toBeNull()
    expect(validateBidAmount('940.00', '940.00')).toBeNull()
    expect(validateBidAmount('939.99', '940.00')).toContain('940.00')
  })

  it('rejects invalid precision and non-positive amounts', () => {
    expect(validateBidAmount('0', '1.00')).toContain('正数')
    expect(validateBidAmount('1.001', '1.00')).toContain('两位小数')
    expect(validateBidAmount('-1.00', '1.00')).toContain('正数')
  })
})
