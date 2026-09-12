package io.github.carpl2.tidebid.auction.domain;

public enum AuctionRegistrationStatus {
    PENDING_HOLD,
    REGISTERED,
    FAILED;

    public boolean canTransitionTo(AuctionRegistrationStatus target) {
        return this == PENDING_HOLD && (target == REGISTERED || target == FAILED);
    }
}
