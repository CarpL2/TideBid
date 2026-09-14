import type {
  AuctionItemCondition,
  AuctionRegistrationStatus,
  AuctionSessionStatus,
  DecimalValue,
} from '@/types/auction'

const SESSION_LABELS: Record<AuctionSessionStatus, string> = {
  SCHEDULED: '即将开始',
  OPEN: '竞价中',
  AWAITING_CLOSE: '等待关拍',
}

const CONDITION_LABELS: Record<AuctionItemCondition, string> = {
  NEW: '全新',
  LIKE_NEW: '几乎全新',
  GOOD: '成色良好',
  FAIR: '正常使用痕迹',
}

const REGISTRATION_LABELS: Record<AuctionRegistrationStatus, string> = {
  PENDING_HOLD: '保证金处理中',
  REGISTERED: '已报名',
  FAILED: '报名失败',
}

export function sessionStatusLabel(status: AuctionSessionStatus): string {
  return SESSION_LABELS[status]
}

export function conditionLabel(condition: AuctionItemCondition): string {
  return CONDITION_LABELS[condition]
}

export function registrationStatusLabel(status: AuctionRegistrationStatus): string {
  return REGISTRATION_LABELS[status]
}

export function formatMoney(value: DecimalValue | null | undefined): string {
  if (value === null || value === undefined) {
    return '--'
  }

  const raw = String(value).trim()
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(raw)
  if (!match) {
    return '--'
  }

  const sign = match[1]
  const integer = match[2]!.replace(/^0+(?=\d)/, '')
  const fraction = (match[3] ?? '').padEnd(2, '0').slice(0, 2)
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return `${sign}¥${grouped}.${fraction}`
}

export function formatShanghaiTime(value: string | null | undefined): string {
  if (!value) {
    return '--'
  }
  const instant = new Date(value)
  if (Number.isNaN(instant.getTime())) {
    return '--'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(instant)
}
