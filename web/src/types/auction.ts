export type DecimalValue = string | number

export type AuctionSessionStatus = 'SCHEDULED' | 'OPEN' | 'AWAITING_CLOSE'
export type AuctionItemCondition = 'NEW' | 'LIKE_NEW' | 'GOOD' | 'FAIR'
export type AuctionRegistrationStatus = 'PENDING_HOLD' | 'REGISTERED' | 'FAILED'
export type AuctionReviewStatus = 'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED'
export type AuctionReviewDecision = 'APPROVED' | 'REJECTED'
export type AuctionCategory =
  | 'ELECTRONICS'
  | 'COLLECTIBLES'
  | 'ART'
  | 'FASHION'
  | 'HOME'
  | 'SPORTS'
  | 'OTHER'

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

export interface AuctionRegistrationRecord {
  registrationId: string
  auctionId: string
  depositAmount: DecimalValue
  status: AuctionRegistrationStatus
  failureCode: string | null
  registeredAt: string | null
  createdAt: string
  updatedAt: string
}

export interface AuctionRegistrationPage {
  page: number
  size: number
  total: number
  totalPages: number
  items: AuctionRegistrationRecord[]
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

export interface AuctionAssetImage extends AuctionImage {
  objectKey: string
  originalFilename: string
}

export interface AuctionAssetReview {
  submissionVersion: number
  decision: AuctionReviewDecision
  comment: string | null
  reviewedAt: string
}

export interface AuctionAssetSummary {
  itemId: string
  auctionId: string
  title: string
  category: AuctionCategory
  itemCondition: AuctionItemCondition
  reviewStatus: AuctionReviewStatus
  sessionStatus: AuctionSessionStatus | 'DRAFT'
  startPrice: DecimalValue
  currentPrice: DecimalValue | null
  startAt: string
  endAt: string
  itemVersion: number
  sessionVersion: number
  coverImage: AuctionAssetImage | null
  createdAt: string
  updatedAt: string
}

export interface AuctionAssetPage {
  page: number
  size: number
  total: number
  totalPages: number
  items: AuctionAssetSummary[]
}

export interface AuctionAssetDetail {
  itemId: string
  auctionId: string
  sellerId: string
  title: string
  description: string
  category: AuctionCategory
  itemCondition: AuctionItemCondition
  reviewStatus: AuctionReviewStatus
  submissionVersion: number
  sessionStatus: AuctionSessionStatus | 'DRAFT'
  startPrice: DecimalValue
  bidIncrement: DecimalValue
  depositAmount: DecimalValue
  currentPrice: DecimalValue | null
  bidCount: number
  startAt: string
  endAt: string
  itemVersion: number
  sessionVersion: number
  submittedAt: string | null
  approvedAt: string | null
  createdAt: string
  updatedAt: string
  images: AuctionAssetImage[]
  latestReview: AuctionAssetReview | null
}

export interface UploadIntentInput {
  originalFilename: string
  contentType: string
  contentLength: number
  checksumSha256: string | null
}

export interface UploadIntent {
  imageId: string
  objectKey: string
  uploadUrl: string
  requiredHeaders: Record<string, string>
  expiresAt: string
}

export interface AuctionDraftFields {
  title: string
  description: string
  category: AuctionCategory
  itemCondition: AuctionItemCondition
  startPrice: string
  bidIncrement: string
  depositAmount: string
  startAt: string
  endAt: string
}

export interface CreateAuctionDraftInput extends AuctionDraftFields {
  imageObjectKeys: string[]
}

export interface UpdateAuctionDraftInput extends AuctionDraftFields {
  itemVersion: number
  sessionVersion: number
}

export interface AuctionDraftResult {
  itemId: string
  auctionId: string
  reviewStatus: AuctionReviewStatus
  sessionStatus: 'DRAFT'
  itemVersion: number
  sessionVersion: number
}

export interface AuctionSubmissionResult {
  itemId: string
  auctionId: string
  reviewStatus: 'PENDING_REVIEW'
  submissionVersion: number
  itemVersion: number
  sessionVersion: number
  submittedAt: string
}

export interface AdminPendingAssetSummary {
  itemId: string
  auctionId: string
  sellerId: string
  title: string
  category: AuctionCategory
  itemCondition: AuctionItemCondition
  reviewStatus: 'PENDING_REVIEW'
  submissionVersion: number
  startPrice: DecimalValue
  bidIncrement: DecimalValue
  depositAmount: DecimalValue
  startAt: string
  endAt: string
  itemVersion: number
  sessionVersion: number
  submittedAt: string
  coverImage: AuctionImage | null
}

export interface AdminPendingAssetPage {
  page: number
  size: number
  total: number
  totalPages: number
  items: AdminPendingAssetSummary[]
}

export interface AdminReviewInput {
  decision: 'APPROVE' | 'REJECT'
  submissionVersion: number
  comment: string | null
}

export interface AdminReviewResult {
  itemId: string
  auctionId: string
  submissionVersion: number
  decision: AuctionReviewDecision
  itemStatus: 'APPROVED' | 'REJECTED'
  sessionStatus: 'SCHEDULED' | 'DRAFT'
  itemVersion: number
  sessionVersion: number
  reviewedAt: string
}

export interface AuctionBidAccepted {
  bidId: string
  auctionId: string
  amount: DecimalValue
  previousPrice: DecimalValue | null
  sequenceNo: number
  createdAt: string
}

export interface AuctionBidConflict {
  auctionId: string
  currentPrice: DecimalValue | null
  minimumNextBid: DecimalValue
  bidCount: number
  version: number
  status: AuctionSessionStatus
}
