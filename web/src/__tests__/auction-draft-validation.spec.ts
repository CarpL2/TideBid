import { describe, expect, it } from 'vitest'

import { validateAuctionDraft } from '@/features/auction/draft-validation'

const validFields = {
  title: '机械键盘',
  description: '保存完好并包含全部原装配件。',
  category: 'ELECTRONICS' as const,
  itemCondition: 'GOOD' as const,
  startPrice: '100.00',
  bidIncrement: '10.00',
  depositAmount: '50.00',
  startAt: '2026-09-15T10:00',
  endAt: '2026-09-15T12:00',
}

describe('auction draft validation', () => {
  it('accepts valid create fields and one image', () => {
    const now = new Date('2026-09-14T10:00:00+08:00').getTime()
    expect(validateAuctionDraft(validFields, 1, true, now)).toEqual([])
  })

  it('rejects invalid money, time and missing create images', () => {
    const now = new Date('2026-09-14T10:00:00+08:00').getTime()
    const errors = validateAuctionDraft(
      {
        ...validFields,
        startPrice: '1.001',
        startAt: '2026-09-14T09:00',
        endAt: '2026-09-14T08:00',
      },
      0,
      true,
      now,
    )
    expect(errors).toContain('起拍价必须为正数且最多保留两位小数。')
    expect(errors).toContain('开始时间至少需要晚于当前时间 1 分钟。')
    expect(errors).toContain('结束时间必须晚于开始时间。')
    expect(errors).toContain('请选择 1～9 张拍品图片。')
  })

  it('does not require selecting images again while editing', () => {
    const now = new Date('2026-09-14T10:00:00+08:00').getTime()
    expect(validateAuctionDraft(validFields, 0, false, now)).toEqual([])
  })
})
