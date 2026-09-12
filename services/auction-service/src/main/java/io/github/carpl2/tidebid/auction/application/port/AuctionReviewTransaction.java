package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionReview;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;

public interface AuctionReviewTransaction {

    ApprovedAuction approve(
            AuctionReview review,
            long expectedItemVersion,
            AuctionSession session
    );

    record ApprovedAuction(AuctionItem item, AuctionSession session, AuctionReview review) {
        public ApprovedAuction {
            if (item == null || session == null || review == null) {
                throw new IllegalArgumentException("item, session and review must not be null");
            }
        }
    }

    final class ReviewConflictException extends RuntimeException {
        public ReviewConflictException(String message) {
            super(message);
        }
    }
}
