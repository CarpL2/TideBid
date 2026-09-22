import { issueRealtimeTicket } from '@/api/realtime'
import type {
  RealtimeAuctionClosed,
  RealtimeAuctionExtended,
  RealtimeBidAccepted,
  RealtimeConnectionState,
  RealtimeServerMessage,
  RealtimeSnapshot,
  RealtimeResyncRequired,
} from '@/types/realtime'

export interface RealtimeAuctionUpdateHandlers {
  onState?: (state: RealtimeConnectionState, detail?: string) => void
  onSnapshot?: (snapshot: RealtimeSnapshot) => void
  onBidAccepted?: (bid: RealtimeBidAccepted) => void
  onAuctionExtended?: (event: RealtimeAuctionExtended) => void
  onAuctionClosed?: (event: RealtimeAuctionClosed) => void
  onResyncRequired?: (event: RealtimeResyncRequired) => void
}

export interface RealtimeAuctionClientOptions {
  websocketUrl?: string
  maxReconnectAttempts?: number
  random?: () => number
  webSocketFactory?: (url: string) => WebSocket
  ticketProvider?: () => Promise<string>
  setTimeout?: typeof globalThis.setTimeout
  clearTimeout?: typeof globalThis.clearTimeout
}

type TimerHandle = ReturnType<typeof globalThis.setTimeout>

const DEFAULT_MAX_RECONNECT_ATTEMPTS = 6

function requestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `web-${crypto.randomUUID()}`
  }
  return `web-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function defaultWebsocketUrl(): string {
  const configured = import.meta.env.VITE_GATEWAY_WS_URL as string | undefined
  if (configured?.trim()) return configured.replace(/\/$/, '')
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const port = window.location.port === '5173' ? ':9000' : window.location.port ? `:${window.location.port}` : ''
  return `${protocol}//${window.location.hostname}${port}`
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

export class RealtimeAuctionClient {
  private readonly handlers: RealtimeAuctionUpdateHandlers
  private readonly websocketUrl: string
  private readonly maxReconnectAttempts: number
  private readonly random: () => number
  private readonly webSocketFactory: (url: string) => WebSocket
  private readonly ticketProvider: () => Promise<string>
  private readonly setTimer: typeof globalThis.setTimeout
  private readonly clearTimer: typeof globalThis.clearTimeout
  private socket: WebSocket | null = null
  private reconnectTimer: TimerHandle | null = null
  private pingTimer: TimerHandle | null = null
  private stopped = true
  private auctionId = ''
  private sequenceNo = 0
  private reconnectAttempts = 0
  private state: RealtimeConnectionState = 'idle'
  private terminal = false
  private paused = false

  constructor(handlers: RealtimeAuctionUpdateHandlers = {}, options: RealtimeAuctionClientOptions = {}) {
    this.handlers = handlers
    this.websocketUrl = options.websocketUrl ?? defaultWebsocketUrl()
    this.maxReconnectAttempts = options.maxReconnectAttempts ?? DEFAULT_MAX_RECONNECT_ATTEMPTS
    this.random = options.random ?? Math.random
    this.webSocketFactory = options.webSocketFactory ?? ((url) => new WebSocket(url))
    this.ticketProvider = options.ticketProvider ?? this.defaultTicketProvider
    this.setTimer = options.setTimeout ?? globalThis.setTimeout
    this.clearTimer = options.clearTimeout ?? globalThis.clearTimeout
  }

  get currentState(): RealtimeConnectionState {
    return this.state
  }

  get lastSequenceNo(): number {
    return this.sequenceNo
  }

  start(auctionId: string, lastSequenceNo = 0): void {
    this.stop(false)
    this.auctionId = auctionId
    this.sequenceNo = Math.max(0, lastSequenceNo)
    this.reconnectAttempts = 0
    this.terminal = false
    this.paused = false
    this.stopped = false
    this.connect()
  }

  stop(markClosed = true): void {
    this.stopped = true
    this.clearReconnectTimer()
    this.clearPingTimer()
    const socket = this.socket
    this.socket = null
    if (socket && socket.readyState !== WebSocket.CLOSED) socket.close(1000, 'client closed')
    if (markClosed) this.setState('closed')
  }

  setSequenceNo(sequenceNo: number): void {
    if (Number.isInteger(sequenceNo) && sequenceNo >= this.sequenceNo) this.sequenceNo = sequenceNo
  }

  notifyOffline(): void {
    if (this.stopped) return
    this.setState('offline', '网络已离线')
    this.clearReconnectTimer()
    this.socket?.close(1001, 'offline')
  }

  pause(): void {
    if (this.stopped || this.terminal || this.paused) return
    this.paused = true
    this.clearReconnectTimer()
    this.clearPingTimer()
    this.socket?.close(1001, 'page hidden')
    this.socket = null
    this.setState('offline', '页面已暂停实时连接')
  }

  resume(): void {
    if (this.stopped || this.terminal || !this.paused) return
    this.paused = false
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      this.setState('offline', '网络已离线')
      return
    }
    this.reconnectAttempts = 0
    this.scheduleReconnect(0)
  }

  notifyOnline(): void {
    if (this.stopped || this.terminal || this.paused || this.socket?.readyState === WebSocket.OPEN) return
    this.reconnectAttempts = 0
    this.scheduleReconnect(0)
  }

  reconnectNow(): void {
    if (this.stopped || this.terminal || this.paused) return
    this.reconnectAttempts = 0
    this.clearReconnectTimer()
    this.socket?.close(1000, 'reconnect')
    this.scheduleReconnect(0)
  }

  private async connect(): Promise<void> {
    if (this.stopped || this.paused || !this.auctionId) return
    this.setState(this.reconnectAttempts > 0 ? 'recovering' : 'connecting')
    try {
      const ticket = await this.ticketProvider()
      if (this.stopped || this.paused) return
      const url = `${this.websocketUrl}/ws/auctions?ticket=${encodeURIComponent(ticket)}`
      const socket = this.webSocketFactory(url)
      this.socket = socket
      socket.onopen = () => this.handleOpen(socket)
      socket.onmessage = (event) => this.handleMessage(event.data)
      socket.onerror = () => this.handleFailure('WebSocket 连接失败')
      socket.onclose = () => this.handleClose(socket)
    } catch {
      this.handleFailure('实时服务暂时不可用')
    }
  }

  private handleOpen(socket: WebSocket): void {
    if (this.stopped || this.paused || socket !== this.socket) return
    this.reconnectAttempts = 0
    this.sendSubscribe()
    this.clearPingTimer()
    this.pingTimer = this.setTimer(() => this.sendPing(), 25_000)
  }

  private handleMessage(raw: unknown): void {
    let message: RealtimeServerMessage
    try {
      const parsed: unknown = typeof raw === 'string' ? JSON.parse(raw) : raw
      if (!isObject(parsed) || typeof parsed.type !== 'string' || !isObject(parsed.payload)) return
      message = parsed as unknown as RealtimeServerMessage
    } catch {
      return
    }

    switch (message.type) {
      case 'SNAPSHOT':
        this.applySnapshot(message.payload as RealtimeSnapshot)
        break
      case 'BID_ACCEPTED':
        this.applyBid(message.payload as RealtimeBidAccepted)
        break
      case 'AUCTION_EXTENDED':
        this.handlers.onAuctionExtended?.(message.payload as RealtimeAuctionExtended)
        break
      case 'AUCTION_CLOSED':
        this.terminal = true
        this.handlers.onAuctionClosed?.(message.payload as RealtimeAuctionClosed)
        this.setState('live')
        this.stop(false)
        break
      case 'RESYNC_REQUIRED':
        this.handlers.onResyncRequired?.(message.payload as RealtimeResyncRequired)
        this.setState('recovering', '正在重新同步')
        this.sendSubscribe()
        break
      case 'ERROR':
        this.setState('recovering', '实时订阅暂时不可用')
        break
      default:
        break
    }
  }

  private applySnapshot(snapshot: RealtimeSnapshot): void {
    if (snapshot.auctionId !== this.auctionId || snapshot.lastSequenceNo < this.sequenceNo) {
      this.setState('recovering', '快照游标不一致')
      this.sendSubscribe()
      return
    }
    this.sequenceNo = snapshot.lastSequenceNo
    this.handlers.onSnapshot?.(snapshot)
    this.setState('live')
  }

  private applyBid(bid: RealtimeBidAccepted): void {
    if (bid.auctionId !== this.auctionId || bid.sequenceNo <= this.sequenceNo) return
    if (bid.sequenceNo !== this.sequenceNo + 1) {
      this.setState('recovering', '发现报价缺口，正在重新同步')
      this.sendSubscribe()
      return
    }
    this.sequenceNo = bid.sequenceNo
    this.handlers.onBidAccepted?.(bid)
    this.setState('live')
  }

  private sendSubscribe(): void {
    if (this.socket?.readyState !== WebSocket.OPEN || !this.auctionId) return
    this.socket.send(JSON.stringify({
      type: 'SUBSCRIBE',
      protocolVersion: 1,
      requestId: requestId(),
      payload: { auctionId: this.auctionId, lastSequenceNo: this.sequenceNo },
    }))
  }

  private sendPing(): void {
    if (this.stopped) return
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify({
        type: 'PING',
        protocolVersion: 1,
        requestId: requestId(),
        payload: { clientTime: new Date().toISOString() },
      }))
    }
    this.pingTimer = this.setTimer(() => this.sendPing(), 25_000)
  }

  private handleFailure(detail: string): void {
    if (this.stopped || this.terminal || this.paused) return
    this.socket = null
    this.clearPingTimer()
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      this.setState('offline', detail)
      return
    }
    this.setState('recovering', detail)
    const delay = Math.min(30_000, 500 * 2 ** this.reconnectAttempts) + Math.floor(this.random() * 250)
    this.reconnectAttempts += 1
    this.scheduleReconnect(delay)
  }

  private handleClose(socket: WebSocket): void {
    if (socket !== this.socket) return
    this.socket = null
    this.clearPingTimer()
    if (!this.stopped && !this.terminal && !this.paused) this.handleFailure('实时连接已断开')
  }

  private scheduleReconnect(delay: number): void {
    this.clearReconnectTimer()
    this.reconnectTimer = this.setTimer(() => {
      this.reconnectTimer = null
      void this.connect()
    }, delay)
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) this.clearTimer(this.reconnectTimer)
    this.reconnectTimer = null
  }

  private clearPingTimer(): void {
    if (this.pingTimer !== null) this.clearTimer(this.pingTimer)
    this.pingTimer = null
  }

  private setState(state: RealtimeConnectionState, detail?: string): void {
    this.state = state
    this.handlers.onState?.(state, detail)
  }

  private async defaultTicketProvider(): Promise<string> {
    const result = await issueRealtimeTicket()
    return result.data.ticket
  }
}
