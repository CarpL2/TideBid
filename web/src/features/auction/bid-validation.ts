import type { DecimalValue } from '@/types/auction'

const MONEY_PATTERN = /^\d{1,17}(?:\.\d{1,2})?$/

function toCents(value: string): bigint {
  const [integer = '0', fraction = ''] = value.split('.')
  return BigInt(integer) * 100n + BigInt(fraction.padEnd(2, '0'))
}

export function validateBidAmount(value: string, minimum: DecimalValue): string | null {
  const normalized = value.trim()
  if (!MONEY_PATTERN.test(normalized) || toCents(normalized) <= 0n) {
    return '报价必须为正数且最多保留两位小数。'
  }

  const minimumText = String(minimum).trim()
  if (!MONEY_PATTERN.test(minimumText)) {
    return '当前最低报价不可用，请刷新场次。'
  }
  if (toCents(normalized) < toCents(minimumText)) {
    return `报价不能低于 ${minimumText} 元。`
  }
  return null
}
