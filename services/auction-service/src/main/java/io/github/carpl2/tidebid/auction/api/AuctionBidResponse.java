package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;

import java.math.BigDecimal;
import java.time.Instant;

public final class AuctionBidResponse {

    private AuctionBidResponse() {
    }

    public record Accepted(
            String bidId,
            String auctionId,
            BigDecimal amount,
            BigDecimal previousPrice,
            long sequenceNo,
            Instant createdAt
    ) {
        static Accepted from(BidRecord source) {
            return new Accepted(
                    Long.toString(source.id()),
                    Long.toString(source.auctionId()),
                    source.amount(),
                    source.previousPrice(),
                    source.sequenceNo(),
                    source.createdAt()
            );
        }
    }

    public record Conflict(
            String auctionId,
            BigDecimal currentPrice,
            BigDecimal minimumNextBid,
            long bidCount,
            long version,
            AuctionSessionStatus status
    ) {
        static Conflict from(
                io.github.carpl2.tidebid.auction.application.AuctionBidConflictException.ConflictSnapshot source
        ) {
            return new Conflict(
                    Long.toString(source.auctionId()),
                    source.currentPrice(),
                    source.minimumNextBid(),
                    source.bidCount(),
                    source.version(),
                    source.status()
            );
        }
    }
}
