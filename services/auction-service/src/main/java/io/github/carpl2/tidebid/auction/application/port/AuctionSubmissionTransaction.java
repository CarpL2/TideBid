package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;

import java.time.Instant;

public interface AuctionSubmissionTransaction {

    SubmittedAuction submit(
            long itemId,
            long sellerId,
            long expectedItemVersion,
            long expectedSessionVersion,
            Instant submittedAt
    );

    record SubmittedAuction(AuctionItem item, AuctionSession session) {
        public SubmittedAuction {
            if (item == null || session == null) {
                throw new IllegalArgumentException("item and session must not be null");
            }
        }
    }

    final class SubmissionConflictException extends RuntimeException {
        public SubmissionConflictException(String message) {
            super(message);
        }
    }
}
