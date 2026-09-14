import type { AuctionDraftFields } from '@/types/auction'

const MONEY_PATTERN = /^\d{1,17}(?:\.\d{1,2})?$/
const MAX_DURATION_MS = 7 * 24 * 60 * 60 * 1000

export function validateAuctionDraft(
  fields: AuctionDraftFields,
  imageCount: number,
  requiresImages: boolean,
  now = Date.now(),
): string[] {
  const errors: string[] = []
  const titleLength = fields.title.trim().length
  const descriptionLength = fields.description.trim().length
  if (titleLength < 2 || titleLength > 80) errors.push('标题需要 2～80 个字符。')
  if (descriptionLength < 10 || descriptionLength > 2000) errors.push('描述需要 10～2000 个字符。')
  for (const [label, value] of [
    ['起拍价', fields.startPrice],
    ['加价幅度', fields.bidIncrement],
    ['保证金', fields.depositAmount],
  ] as const) {
    if (!MONEY_PATTERN.test(value) || Number(value) <= 0) {
      errors.push(`${label}必须为正数且最多保留两位小数。`)
    }
  }
  const startAt = new Date(fields.startAt).getTime()
  const endAt = new Date(fields.endAt).getTime()
  if (!Number.isFinite(startAt) || startAt < now + 60_000) errors.push('开始时间至少需要晚于当前时间 1 分钟。')
  if (!Number.isFinite(endAt) || endAt <= startAt) errors.push('结束时间必须晚于开始时间。')
  if (Number.isFinite(startAt) && Number.isFinite(endAt) && endAt - startAt > MAX_DURATION_MS) {
    errors.push('单场拍卖最长不能超过 7 天。')
  }
  if (requiresImages && (imageCount < 1 || imageCount > 9)) errors.push('请选择 1～9 张拍品图片。')
  return errors
}

export function toLocalDateTimeInput(date: Date): string {
  const shifted = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return shifted.toISOString().slice(0, 16)
}

export function localDateTimeToInstant(value: string): string {
  return new Date(value).toISOString()
}
