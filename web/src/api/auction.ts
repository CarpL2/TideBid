import { requestData } from '@/api/http'
import type { ApiResult } from '@/types/api'
import type {
  AuctionBidHistoryPage,
  AuctionDetail,
  AuctionLobbyPage,
} from '@/types/auction'

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
