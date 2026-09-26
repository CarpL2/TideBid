import { describe, expect, it } from 'vitest'

import { mergeRealtimeSnapshotBids } from '@/features/auction/bid-history'

describe('realtime snapshot bid history merge', () => {
  it('preserves HTTP history when a resume snapshot contains no newer bids', () => {
    const current = [
      { bidId: '1', amount: '100.00', previousPrice: null, sequenceNo: 1, createdAt: '2026-09-25T08:00:00Z', mine: false },
      { bidId: '2', amount: '150.00', previousPrice: '100.00', sequenceNo: 2, createdAt: '2026-09-25T08:01:00Z', mine: true },
    ]

    expect(mergeRealtimeSnapshotBids(current, [])).toEqual(current)
  })

  it('deduplicates overlap and appends newer snapshot bids in sequence order', () => {
    const current = [
      { bidId: '1', amount: '100.00', previousPrice: null, sequenceNo: 1, createdAt: '2026-09-25T08:00:00Z', mine: false },
      { bidId: '2', amount: '150.00', previousPrice: '100.00', sequenceNo: 2, createdAt: '2026-09-25T08:01:00Z', mine: true },
    ]
    const incoming = [
      { bidId: '2', amount: '150.00', sequenceNo: 2, acceptedAt: '2026-09-25T08:01:00Z', mine: true },
      { bidId: '3', amount: '160.00', sequenceNo: 3, acceptedAt: '2026-09-25T08:01:01Z', mine: false },
    ]

    const merged = mergeRealtimeSnapshotBids(current, incoming)

    expect(merged.map((bid) => bid.sequenceNo)).toEqual([1, 2, 3])
    expect(merged[2]).toMatchObject({ bidId: '3', amount: '160.00', mine: false })
  })
})
