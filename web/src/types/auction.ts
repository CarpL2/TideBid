export type DecimalValue = string | number

export type AuctionSessionStatus = 'SCHEDULED' | 'OPEN' | 'AWAITING_CLOSE'
export type AuctionItemCondition = 'NEW' | 'LIKE_NEW' | 'GOOD' | 'FAIR'
export type AuctionRegistrationStatus = 'PENDING_HOLD' | 'REGISTERED' | 'FAILED'

export interface AuctionCoverImage {
  imageId: string
  contentType: string
  previewUrl: string | null
  previewExpiresAt: string | null
}

export interface AuctionLobbyItem {
  itemId: string
  auctionId: string
  title: string
  category: string
  itemCondition: AuctionItemCondition
  sessionStatus: AuctionSessionStatus
  startPrice: DecimalValue
  currentPrice: DecimalValue | null
  displayPrice: DecimalValue
  minimumNextBid: DecimalValue
  bidCount: number
  startAt: string
  endAt: string
  coverImage: AuctionCoverImage | null
}

export interface AuctionLobbyPage {
  page: number
  size: number
  total: number
  totalPages: number
  items: AuctionLobbyItem[]
}

export interface AuctionImage extends AuctionCoverImage {
  contentLength: number
  sortOrder: number
}

export interface AuctionRegistrationSummary {
  registrationId: string
  status: AuctionRegistrationStatus
  failureCode: string | null
  registeredAt: string | null
}

export interface AuctionDetail {
  itemId: string
  auctionId: string
  title: string
  description: string
  category: string
  itemCondition: AuctionItemCondition
  sessionStatus: AuctionSessionStatus
  startPrice: DecimalValue
  bidIncrement: DecimalValue
  depositAmount: DecimalValue
  currentPrice: DecimalValue | null
  displayPrice: DecimalValue
  minimumNextBid: DecimalValue
  bidCount: number
  startAt: string
  endAt: string
  ownedByCurrentUser: boolean
  images: AuctionImage[]
  myRegistration: AuctionRegistrationSummary | null
}

export interface AuctionBidHistoryItem {
  bidId: string
  amount: DecimalValue
  previousPrice: DecimalValue | null
  sequenceNo: number
  createdAt: string
  mine: boolean
}

export interface AuctionBidHistoryPage {
  auctionId: string
  page: number
  size: number
  total: number
  totalPages: number
  items: AuctionBidHistoryItem[]
}
