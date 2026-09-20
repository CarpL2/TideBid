package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionProxyBid;

import java.util.List;
import java.util.Optional;

public interface AuctionProxyBidRepository {
    AuctionProxyBid insert(AuctionProxyBid proxyBid);

    boolean update(AuctionProxyBid proxyBid);

    Optional<AuctionProxyBid> findByAuctionAndBidder(long auctionId, long bidderId);

    List<AuctionProxyBid> findActiveByAuction(long auctionId);
}
