import type { AuctionSessionStatus } from '@/types/auction'

export type RealtimeConnectionState =
  | 'idle'
  | 'connecting'
  | 'live'
  | 'recovering'
  | 'offline'
  | 'closed'

export interface RealtimeTicketIssue {
  ticket: string
  expiresAt: string
}

export interface RealtimeBidView {
  bidId: string
  amount: string | number
  sequenceNo: number
  mine: boolean
  acceptedAt: string
}

export interface RealtimeSnapshot {
  auctionId: string
  status: AuctionSessionStatus
  displayPrice: string | number
  minimumNextBid: string | number
  bidCount: number
  endAt: string
  closedAt: string | null
  extensionCount: number
  lastSequenceNo: number
  leading: boolean
  proxyActive: boolean
  bids: RealtimeBidView[]
}

export interface RealtimeBidAccepted {
  eventId: string
  auctionId: string
  bidId: string
  amount: string | number
  sequenceNo: number
  mine: boolean
  acceptedAt: string
}

export interface RealtimeAuctionExtended {
  eventId: string
  auctionId: string
  previousEndAt: string
  endAt: string
  extensionCount: number
  extendedAt: string
}

export interface RealtimeAuctionClosed {
  eventId: string
  auctionId: string
  status: Extract<AuctionSessionStatus, 'CLOSED_SOLD' | 'CLOSED_UNSOLD'>
  finalPrice: string | number | null
  wonByCurrentUser: boolean
  closedAt: string
}

export interface RealtimeResyncRequired {
  auctionId: string
  reason: 'HISTORY_GAP' | 'BUFFER_OVERFLOW' | 'SNAPSHOT_INCONSISTENT'
  lastKnownSequenceNo: number
}

export interface RealtimeServerMessage<T = unknown> {
  type: string
  protocolVersion: number
  requestId: string
  sentAt: string
  payload: T
}

