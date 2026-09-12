package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.port.AuctionReviewTransaction;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionReviewDecision;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;

import java.time.Instant;

public record AdminReviewResponse(
        String itemId,
        String auctionId,
        int submissionVersion,
        AuctionReviewDecision decision,
        AuctionItemReviewStatus itemStatus,
        AuctionSessionStatus sessionStatus,
        long itemVersion,
        long sessionVersion,
        Instant reviewedAt
) {
    static AdminReviewResponse from(AuctionReviewTransaction.ReviewedAuction source) {
        return new AdminReviewResponse(
                Long.toString(source.item().id()), Long.toString(source.session().id()),
                source.review().submissionVersion(), source.review().decision(), source.item().reviewStatus(),
                source.session().status(), source.item().version(), source.session().version(),
                source.review().reviewedAt()
        );
    }
}
