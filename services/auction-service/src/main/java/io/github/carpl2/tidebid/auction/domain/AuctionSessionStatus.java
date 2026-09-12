package io.github.carpl2.tidebid.auction.domain;

public enum AuctionSessionStatus {
    DRAFT,
    SCHEDULED,
    OPEN,
    AWAITING_CLOSE;

    public boolean canTransitionTo(AuctionSessionStatus target) {
        return switch (this) {
            case DRAFT -> target == SCHEDULED;
            case SCHEDULED -> target == OPEN;
            case OPEN -> target == AWAITING_CLOSE;
            case AWAITING_CLOSE -> false;
        };
    }
}
