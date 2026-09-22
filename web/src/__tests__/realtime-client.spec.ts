import { beforeEach, describe, expect, it } from 'vitest'

import { RealtimeAuctionClient } from '@/features/realtime/client'

class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  readonly sent: string[] = []
  readonly url: string
  readyState = 0
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onerror: (() => void) | null = null
  onclose: (() => void) | null = null

  constructor(url: string) {
    this.url = url
    FakeWebSocket.instances.push(this)
  }

  send(value: string): void {
    this.sent.push(value)
  }

  close(): void {
    this.readyState = 3
    this.onclose?.()
  }

  open(): void {
    this.readyState = 1
    this.onopen?.()
  }

  message(payload: unknown): void {
    this.onmessage?.({ data: JSON.stringify(payload) })
  }
}

describe('RealtimeAuctionClient', () => {
  beforeEach(() => {
    FakeWebSocket.instances = []
  })

  it('subscribes with lastSequenceNo and ignores duplicate or gapped bids', async () => {
    const states: string[] = []
    const bids: number[] = []
    const snapshots: number[] = []
    const client = new RealtimeAuctionClient({
      onState: (state) => states.push(state),
      onSnapshot: (snapshot) => snapshots.push(snapshot.lastSequenceNo),
      onBidAccepted: (bid) => bids.push(bid.sequenceNo),
    }, {
      ticketProvider: async () => 'ticket-value',
      webSocketFactory: (url: string) => new FakeWebSocket(url) as unknown as WebSocket,
      setTimeout: (() => 1 as unknown as ReturnType<typeof setTimeout>) as unknown as typeof setTimeout,
      clearTimeout: (() => undefined) as unknown as typeof clearTimeout,
    })

    client.start('42', 2)
    await Promise.resolve()
    const socket = FakeWebSocket.instances[0]!
    socket.open()
    const subscribe = JSON.parse(socket.sent[0]!) as { payload: { lastSequenceNo: number } }
    expect(subscribe.payload.lastSequenceNo).toBe(2)
    socket.message({
      type: 'SNAPSHOT', protocolVersion: 1, requestId: 'r', sentAt: new Date().toISOString(),
      payload: {
        auctionId: '42', status: 'OPEN', displayPrice: '10.00', minimumNextBid: '11.00',
        bidCount: 2, endAt: new Date().toISOString(), closedAt: null, extensionCount: 0,
        lastSequenceNo: 2, leading: false, proxyActive: false, bids: [],
      },
    })
    socket.message({
      type: 'BID_ACCEPTED', protocolVersion: 1, requestId: 'e', sentAt: new Date().toISOString(),
      payload: { eventId: 'e', auctionId: '42', bidId: '3', amount: '11.00', sequenceNo: 3, mine: true, acceptedAt: new Date().toISOString() },
    })
    socket.message({
      type: 'BID_ACCEPTED', protocolVersion: 1, requestId: 'e', sentAt: new Date().toISOString(),
      payload: { eventId: 'e', auctionId: '42', bidId: '3', amount: '11.00', sequenceNo: 3, mine: true, acceptedAt: new Date().toISOString() },
    })

    expect(snapshots).toEqual([2])
    expect(bids).toEqual([3])
    expect(states).toContain('live')
  })

  it('backs off a failed connection and reaches offline after bounded attempts', async () => {
    const states: string[] = []
    const timers: TimerHandler[] = []
    const client = new RealtimeAuctionClient({
      onState: (state) => states.push(state),
    }, {
      maxReconnectAttempts: 1,
      ticketProvider: async () => 'ticket-value',
      webSocketFactory: (url: string) => new FakeWebSocket(url) as unknown as WebSocket,
      random: () => 0,
      setTimeout: ((callback: TimerHandler) => {
        timers.push(callback)
        return timers.length as unknown as ReturnType<typeof setTimeout>
      }) as unknown as typeof setTimeout,
      clearTimeout: (() => undefined) as unknown as typeof clearTimeout,
    })

    client.start('42')
    await Promise.resolve()
    FakeWebSocket.instances[0]!.onerror?.()
    await Promise.resolve()
    const reconnect = timers.find((callback) => typeof callback === 'function')
    if (typeof reconnect === 'function') reconnect()
    await Promise.resolve()
    FakeWebSocket.instances[1]!.onerror?.()
    expect(states).toContain('offline')
  })
})
