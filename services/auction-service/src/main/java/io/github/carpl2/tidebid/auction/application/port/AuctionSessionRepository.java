package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.BidRecord;

import java.util.Optional;

public interface AuctionSessionRepository {

    AuctionSession insertSession(AuctionSession session);

    Optional<AuctionSession> findSessionById(long auctionId);

    Optional<AuctionSession> findSessionByItemId(long itemId);

    BidRecord insertBid(BidRecord bid);

    Optional<BidRecord> findBid(long bidderId, String requestId);
}
