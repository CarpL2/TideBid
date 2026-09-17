package io.github.carpl2.tidebid.auction.domain;

public enum AuctionSessionStatus {
    DRAFT,
    SCHEDULED,
    OPEN,
    AWAITING_CLOSE,
    CLOSED_SOLD,
    CLOSED_UNSOLD;

    public boolean canTransitionTo(AuctionSessionStatus target) {
        return switch (this) {
            case DRAFT -> target == SCHEDULED;
            case SCHEDULED -> target == OPEN;
            case OPEN -> target == AWAITING_CLOSE;
            case AWAITING_CLOSE -> target == CLOSED_SOLD || target == CLOSED_UNSOLD;
            case CLOSED_SOLD, CLOSED_UNSOLD -> false;
        };
    }
}
