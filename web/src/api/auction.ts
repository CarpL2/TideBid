import { requestData } from '@/api/http'
import { ApiError } from '@/api/errors'
import type { ApiResult } from '@/types/api'
import type {
  AuctionAssetDetail,
  AuctionAssetPage,
  AuctionBidAccepted,
  AuctionBidHistoryPage,
  AuctionProxyBidDetail,
  AuctionProxyBidResult,
  AuctionDetail,
  AuctionDraftResult,
  AuctionLobbyPage,
  AuctionRegistrationPage,
  AuctionRegistrationRecord,
  AdminPendingAssetPage,
  AdminReviewInput,
  AdminReviewResult,
  AuctionSubmissionResult,
  CreateAuctionDraftInput,
  UpdateAuctionDraftInput,
  UploadIntent,
  UploadIntentInput,
} from '@/types/auction'

export function getMyAuctionProxyBid(
  auctionId: string,
): Promise<ApiResult<AuctionProxyBidDetail | null>> {
  return requestData<AuctionProxyBidDetail | null>({
    method: 'GET',
    url: `/auctions/${encodeURIComponent(auctionId)}/proxy-bid`,
  })
}

export function upsertAuctionProxyBid(
  auctionId: string,
  maxAmount: string,
  requestId: string,
): Promise<ApiResult<AuctionProxyBidResult>> {
  return requestData<AuctionProxyBidResult>({
    method: 'PUT',
    url: `/auctions/${encodeURIComponent(auctionId)}/proxy-bid`,
    headers: { 'X-Request-Id': requestId },
    data: { maxAmount },
  })
}

export function disableAuctionProxyBid(
  auctionId: string,
  requestId: string,
): Promise<ApiResult<AuctionProxyBidResult>> {
  return requestData<AuctionProxyBidResult>({
    method: 'DELETE',
    url: `/auctions/${encodeURIComponent(auctionId)}/proxy-bid`,
    headers: { 'X-Request-Id': requestId },
  })
}

export function getAuctionLobby(page = 1, size = 12): Promise<ApiResult<AuctionLobbyPage>> {
  return requestData<AuctionLobbyPage>({
    method: 'GET',
    url: '/auctions',
    params: { page, size },
  })
}

export function getAuctionDetail(auctionId: string): Promise<ApiResult<AuctionDetail>> {
  return requestData<AuctionDetail>({
    method: 'GET',
    url: `/auctions/${encodeURIComponent(auctionId)}`,
  })
}

export function getAuctionBidHistory(
  auctionId: string,
  page = 1,
  size = 10,
): Promise<ApiResult<AuctionBidHistoryPage>> {
  return requestData<AuctionBidHistoryPage>({
    method: 'GET',
    url: `/auctions/${encodeURIComponent(auctionId)}/bids`,
    params: { page, size },
  })
}

export function getMyAuctionAssets(page = 1, size = 12): Promise<ApiResult<AuctionAssetPage>> {
  return requestData<AuctionAssetPage>({
    method: 'GET',
    url: '/assets/mine',
    params: { page, size },
  })
}

export function getAuctionAsset(assetId: string): Promise<ApiResult<AuctionAssetDetail>> {
  return requestData<AuctionAssetDetail>({
    method: 'GET',
    url: `/assets/${encodeURIComponent(assetId)}`,
  })
}

export function createUploadIntent(input: UploadIntentInput): Promise<ApiResult<UploadIntent>> {
  return requestData<UploadIntent>({
    method: 'POST',
    url: '/assets/upload-intents',
    data: input,
  })
}

export async function putObjectToSignedUrl(intent: UploadIntent, file: File): Promise<void> {
  const headers = new Headers()
  for (const [name, value] of Object.entries(intent.requiredHeaders)) {
    const normalized = name.toLowerCase()
    if (normalized !== 'content-length' && normalized !== 'host') {
      headers.set(name, value)
    }
  }
  if (!headers.has('Content-Type')) {
    headers.set('Content-Type', file.type)
  }

  let response: Response
  try {
    response = await fetch(intent.uploadUrl, { method: 'PUT', headers, body: file })
  } catch (cause) {
    throw new ApiError({
      code: 'CLIENT_UPLOAD_NETWORK_ERROR',
      status: 0,
      userMessage: '图片直传失败，请检查 OSS 跨域配置和网络后重试。',
      cause,
    })
  }
  if (!response.ok) {
    throw new ApiError({
      code: 'CLIENT_UPLOAD_FAILED',
      status: response.status,
      userMessage: '图片直传未完成，请重新选择图片后再试。',
    })
  }
}

export function createAuctionDraft(
  input: CreateAuctionDraftInput,
): Promise<ApiResult<AuctionDraftResult>> {
  return requestData<AuctionDraftResult>({ method: 'POST', url: '/assets', data: input })
}

export function updateAuctionDraft(
  assetId: string,
  input: UpdateAuctionDraftInput,
): Promise<ApiResult<AuctionDraftResult>> {
  return requestData<AuctionDraftResult>({
    method: 'PUT',
    url: `/assets/${encodeURIComponent(assetId)}`,
    data: input,
  })
}

export function submitAuctionAsset(
  assetId: string,
  itemVersion: number,
  sessionVersion: number,
): Promise<ApiResult<AuctionSubmissionResult>> {
  return requestData<AuctionSubmissionResult>({
    method: 'POST',
    url: `/assets/${encodeURIComponent(assetId)}/submit`,
    data: { itemVersion, sessionVersion },
  })
}

export function getPendingAuctionAssets(
  page = 1,
  size = 12,
): Promise<ApiResult<AdminPendingAssetPage>> {
  return requestData<AdminPendingAssetPage>({
    method: 'GET',
    url: '/admin/assets/pending',
    params: { page, size },
  })
}

export function reviewAuctionAsset(
  assetId: string,
  input: AdminReviewInput,
): Promise<ApiResult<AdminReviewResult>> {
  return requestData<AdminReviewResult>({
    method: 'POST',
    url: `/admin/assets/${encodeURIComponent(assetId)}/reviews`,
    data: input,
  })
}

export function getMyAuctionRegistrations(
  page = 1,
  size = 12,
): Promise<ApiResult<AuctionRegistrationPage>> {
  return requestData<AuctionRegistrationPage>({
    method: 'GET',
    url: '/registrations/mine',
    params: { page, size },
  })
}

export function getAuctionRegistration(
  registrationId: string,
): Promise<ApiResult<AuctionRegistrationRecord>> {
  return requestData<AuctionRegistrationRecord>({
    method: 'GET',
    url: `/registrations/${encodeURIComponent(registrationId)}`,
  })
}

export function registerForAuction(
  auctionId: string,
): Promise<ApiResult<AuctionRegistrationRecord>> {
  return requestData<AuctionRegistrationRecord>({
    method: 'POST',
    url: '/registrations',
    data: { auctionId },
  })
}

export function placeAuctionBid(
  auctionId: string,
  amount: string,
  requestId: string,
): Promise<ApiResult<AuctionBidAccepted>> {
  return requestData<AuctionBidAccepted>({
    method: 'POST',
    url: '/bids',
    headers: { 'X-Request-Id': requestId },
    data: { auctionId, amount },
  })
}
