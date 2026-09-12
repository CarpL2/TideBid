package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionReview;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;

public interface AuctionReviewTransaction {

    ReviewedAuction approve(
            AuctionReview review,
            long expectedItemVersion,
            AuctionSession session
    );

    ReviewedAuction reject(
            AuctionReview review,
            long expectedItemVersion,
            AuctionSession session
    );

    record ReviewedAuction(AuctionItem item, AuctionSession session, AuctionReview review) {
        public ReviewedAuction {
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
