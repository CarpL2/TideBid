package io.github.carpl2.tidebid.auction.domain;

import java.time.Instant;
import java.util.Objects;

public record AuctionReview(
        long id,
        long itemId,
        int submissionVersion,
        long reviewerId,
        AuctionReviewDecision decision,
        String comment,
        Instant reviewedAt
) {
    public AuctionReview {
        AuctionDomainRules.positiveId(id, "id");
        AuctionDomainRules.positiveId(itemId, "itemId");
        if (submissionVersion <= 0) {
            throw new IllegalArgumentException("submissionVersion must be positive");
        }
        AuctionDomainRules.positiveId(reviewerId, "reviewerId");
        Objects.requireNonNull(decision, "decision must not be null");
        comment = AuctionDomainRules.optionalText(comment, 500, "comment");
        reviewedAt = AuctionDomainRules.instant(reviewedAt, "reviewedAt");
    }
}
