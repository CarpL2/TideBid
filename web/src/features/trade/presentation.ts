import type {
  SellerSettlementStatus,
  TradeOrderStatus,
  TradePaymentStatus,
} from '@/types/trade'

const ORDER_STATUS_LABELS: Record<TradeOrderStatus, string> = {
  PENDING_DEPOSIT: '保证金结算中',
  PENDING_PAYMENT: '待支付尾款',
  PAYMENT_PROCESSING: '支付结果确认中',
  PAID: '已支付',
  PAYMENT_TIMEOUT: '支付超时',
}

const SETTLEMENT_STATUS_LABELS: Record<SellerSettlementStatus, string> = {
  NOT_REQUIRED: '尚未触发',
  PENDING: '卖家入账中',
  COMPLETED: '卖家已入账',
}

const PAYMENT_STATUS_LABELS: Record<TradePaymentStatus, string> = {
  PROCESSING: '扣款处理中',
  UNKNOWN: '扣款结果确认中',
  SUCCEEDED: '支付成功',
  REJECTED: '支付未完成',
}

const PAYMENT_FAILURE_LABELS: Record<string, string> = {
  ACCOUNT_WALLET_INSUFFICIENT_BALANCE: '钱包可用余额不足，请充值或调整余额后，在截止时间前重新支付。',
  ACCOUNT_DISABLED: '当前账户已停用，无法完成支付。',
  ACCOUNT_NOT_FOUND: '支付账户不存在，请联系管理员。',
  ACCOUNT_WALLET_NOT_FOUND: '当前账户尚未创建钱包，请联系管理员。',
}

export function orderStatusLabel(status: TradeOrderStatus): string {
  return ORDER_STATUS_LABELS[status]
}

export function orderStatusTagType(
  status: TradeOrderStatus,
): 'info' | 'warning' | 'success' | 'danger' {
  if (status === 'PAID') return 'success'
  if (status === 'PAYMENT_TIMEOUT') return 'danger'
  if (status === 'PENDING_PAYMENT' || status === 'PAYMENT_PROCESSING') return 'warning'
  return 'info'
}

export function settlementStatusLabel(status: SellerSettlementStatus): string {
  return SETTLEMENT_STATUS_LABELS[status]
}

export function paymentStatusLabel(status: TradePaymentStatus): string {
  return PAYMENT_STATUS_LABELS[status]
}

export function paymentFailureLabel(code: string | null): string {
  if (!code) return '支付未完成，请稍后重试。'
  return PAYMENT_FAILURE_LABELS[code] ?? '支付未完成，请检查钱包状态后重试。'
}

export function maskUserId(userId: string): string {
  if (userId.length <= 4) return `${userId.slice(0, 1)}***`
  return `${userId.slice(0, 2)}***${userId.slice(-2)}`
}
