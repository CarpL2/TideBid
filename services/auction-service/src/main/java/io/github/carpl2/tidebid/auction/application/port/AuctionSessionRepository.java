package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.BidRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuctionSessionRepository {

    AuctionSession insertSession(AuctionSession session);

    Optional<AuctionSession> findSessionById(long auctionId);

    Optional<AuctionSession> findSessionByItemId(long itemId);

    List<AuctionSession> findSessionsByItemIds(List<Long> itemIds);

    boolean updateDraftSession(AuctionSession session);

    boolean scheduleDraftSession(
            long auctionId,
            long itemId,
            long sellerId,
            long expectedVersion,
            Instant scheduledAt
    );

    BidRecord insertBid(BidRecord bid);

    Optional<BidRecord> findBid(long bidderId, String requestId);
}
