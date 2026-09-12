package io.github.carpl2.tidebid.auction.domain;

public enum AuctionItemReviewStatus {
    DRAFT,
    PENDING_REVIEW,
    APPROVED,
    REJECTED;

    public boolean canTransitionTo(AuctionItemReviewStatus target) {
        return switch (this) {
            case DRAFT, REJECTED -> target == PENDING_REVIEW;
            case PENDING_REVIEW -> target == APPROVED || target == REJECTED;
            case APPROVED -> false;
        };
    }
}
