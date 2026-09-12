package io.github.carpl2.tidebid.auction.domain;

import java.time.Instant;
import java.util.Objects;

public record AuctionItem(
        long id,
        long sellerId,
        String title,
        String description,
        String category,
        AuctionItemCondition itemCondition,
        AuctionItemReviewStatus reviewStatus,
        int submissionVersion,
        long version,
        Instant submittedAt,
        Instant approvedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public AuctionItem {
        AuctionDomainRules.positiveId(id, "id");
        AuctionDomainRules.positiveId(sellerId, "sellerId");
        title = AuctionDomainRules.text(title, 2, 80, "title");
        description = AuctionDomainRules.text(description, 10, 2000, "description");
        category = AuctionDomainRules.text(category, 2, 32, "category");
        Objects.requireNonNull(itemCondition, "itemCondition must not be null");
        Objects.requireNonNull(reviewStatus, "reviewStatus must not be null");
        AuctionDomainRules.nonNegative(submissionVersion, "submissionVersion");
        if (reviewStatus == AuctionItemReviewStatus.DRAFT
                && (submissionVersion != 0 || submittedAt != null || approvedAt != null)) {
            throw new IllegalArgumentException("draft item cannot contain submission or approval data");
        }
        if (reviewStatus != AuctionItemReviewStatus.DRAFT
                && (submissionVersion == 0 || submittedAt == null)) {
            throw new IllegalArgumentException("reviewed item requires a submitted version and time");
        }
        if ((reviewStatus == AuctionItemReviewStatus.APPROVED) != (approvedAt != null)) {
            throw new IllegalArgumentException("approvedAt must be present only for an approved item");
        }
        AuctionDomainRules.nonNegative(version, "version");
        createdAt = AuctionDomainRules.instant(createdAt, "createdAt");
        updatedAt = AuctionDomainRules.instant(updatedAt, "updatedAt");
        if (submittedAt != null && submittedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("submittedAt must not be before createdAt");
        }
        if (approvedAt != null && approvedAt.isBefore(submittedAt)) {
            throw new IllegalArgumentException("approvedAt must not be before submittedAt");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
