package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;

import java.math.BigDecimal;

public final class AuctionBidConflictException extends RuntimeException {

    private final ConflictSnapshot snapshot;

    public AuctionBidConflictException(AuctionSession latestSession) {
        super(AuctionErrorCode.BID_CONFLICT.defaultMessage());
        if (latestSession == null) {
            throw new IllegalArgumentException("latestSession must not be null");
        }
        this.snapshot = new ConflictSnapshot(
                latestSession.id(),
                latestSession.currentPrice(),
                latestSession.minimumNextBid(),
                latestSession.bidCount(),
                latestSession.version(),
                latestSession.status()
        );
    }

    public AuctionErrorCode errorCode() {
        return AuctionErrorCode.BID_CONFLICT;
    }

    public ConflictSnapshot snapshot() {
        return snapshot;
    }

    public record ConflictSnapshot(
            long auctionId,
            BigDecimal currentPrice,
            BigDecimal minimumNextBid,
            long bidCount,
            long version,
            AuctionSessionStatus status
    ) {
    }
}
