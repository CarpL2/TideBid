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

    List<AuctionSession> findDueScheduledSessions(Instant dueAt, int limit);

    LobbySessionPage findLobbySessions(int offset, int limit);

    boolean updateDraftSession(AuctionSession session);

    boolean scheduleDraftSession(
            long auctionId,
            long itemId,
            long sellerId,
            long expectedVersion,
            Instant scheduledAt
    );

    boolean openScheduledSession(long auctionId, long expectedVersion, Instant openedAt);

    boolean markOpenSessionAwaitingClose(long auctionId, long expectedVersion, Instant endedAt);

    BidRecord insertBid(BidRecord bid);

    Optional<BidRecord> findBid(long bidderId, String requestId);

    BidPage findBidsByAuction(long auctionId, int offset, int limit);

    List<BidRecord> findBidsAfterSequence(long auctionId, long afterSequenceNo, int limit);

    record LobbySessionPage(List<AuctionSession> sessions, long total) {
        public LobbySessionPage {
            sessions = List.copyOf(sessions);
            if (total < sessions.size()) {
                throw new IllegalArgumentException("total must not be below the returned session count");
            }
        }
    }

    record BidPage(List<BidRecord> bids, long total) {
        public BidPage {
            bids = List.copyOf(bids);
            if (total < bids.size()) {
                throw new IllegalArgumentException("total must not be below the returned bid count");
            }
        }
    }
}
