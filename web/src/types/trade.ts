export type TradeOrderStatus =
  | 'PENDING_DEPOSIT'
  | 'PENDING_PAYMENT'
  | 'PAYMENT_PROCESSING'
  | 'PAID'
  | 'PAYMENT_TIMEOUT'

export type SellerSettlementStatus = 'NOT_REQUIRED' | 'PENDING' | 'COMPLETED'
export type TradePaymentStatus = 'PROCESSING' | 'UNKNOWN' | 'SUCCEEDED' | 'REJECTED'

export interface TradeOrder {
  orderId: string
  orderNo: string
  auctionId: string
  itemId: string
  sellerId: string
  buyerId: string
  itemTitle: string
  finalPrice: string
  capturedDepositAmount: string
  payableAmount: string
  status: TradeOrderStatus
  paymentDeadline: string | null
  paidAt: string | null
  timedOutAt: string | null
  sellerSettlementStatus: SellerSettlementStatus
  sellerReceivableAmount: string | null
  sellerCreditedAt: string | null
  auctionClosedAt: string
  createdAt: string
  updatedAt: string
  paymentEligible: boolean
}

export interface TradeOrderPage {
  page: number
  size: number
  total: number
  totalPages: number
  items: TradeOrder[]
}

export interface TradePaymentAttempt {
  paymentAttemptId: string
  paymentNo: string
  orderId: string
  amount: string
  status: TradePaymentStatus
  failureCode: string | null
  nextRecoveryAt: string | null
  completedAt: string | null
  createdAt: string
  updatedAt: string
}
