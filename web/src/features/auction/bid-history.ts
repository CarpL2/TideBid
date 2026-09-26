import type { AuctionBidHistoryItem } from '@/types/auction'
import type { RealtimeBidView } from '@/types/realtime'

export function mergeRealtimeSnapshotBids(
  current: AuctionBidHistoryItem[],
  incoming: RealtimeBidView[],
  limit = 10,
): AuctionBidHistoryItem[] {
  const merged = new Map<number, AuctionBidHistoryItem>()
  for (const bid of current) merged.set(bid.sequenceNo, bid)
  for (const bid of incoming) {
    merged.set(bid.sequenceNo, {
      bidId: bid.bidId,
      amount: bid.amount,
      previousPrice: null,
      sequenceNo: bid.sequenceNo,
      createdAt: bid.acceptedAt,
      mine: bid.mine,
    })
  }
  return [...merged.values()]
    .sort((left, right) => left.sequenceNo - right.sequenceNo)
    .slice(-limit)
}
