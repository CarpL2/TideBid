package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.BidRecord;

public interface AuctionBidTransaction {

    AcceptedBid accept(BidRecord bid, long expectedSessionVersion);

    record AcceptedBid(AuctionSession session, BidRecord bid) {
        public AcceptedBid {
            if (session == null || bid == null || session.id() != bid.auctionId()) {
                throw new IllegalArgumentException("accepted session and bid must belong to the same auction");
            }
        }
    }

    final class BidConflictException extends RuntimeException {
        public BidConflictException() {
            super("Auction session changed before the bid was accepted");
        }
    }

    final class DuplicateBidException extends RuntimeException {
        public DuplicateBidException(Throwable cause) {
            super("Bid idempotency or sequence key already exists", cause);
        }
    }
}
