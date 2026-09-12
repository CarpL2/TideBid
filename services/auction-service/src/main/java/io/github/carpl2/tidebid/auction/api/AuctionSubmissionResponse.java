package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.port.AuctionSubmissionTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;

import java.time.Instant;

public record AuctionSubmissionResponse(
        String itemId,
        String auctionId,
        AuctionItemReviewStatus reviewStatus,
        int submissionVersion,
        long itemVersion,
        long sessionVersion,
        Instant submittedAt
) {
    static AuctionSubmissionResponse from(AuctionSubmissionTransaction.SubmittedAuction source) {
        return new AuctionSubmissionResponse(
                Long.toString(source.item().id()),
                Long.toString(source.session().id()),
                source.item().reviewStatus(),
                source.item().submissionVersion(),
                source.item().version(),
                source.session().version(),
                source.item().submittedAt()
        );
    }
}
