package io.github.carpl2.tidebid.auction.api;

import io.github.carpl2.tidebid.auction.application.AuctionRealtimeSnapshotService;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AuctionRealtimeSnapshotResponse(
        String auctionId,
        AuctionSessionStatus status,
        BigDecimal displayPrice,
        BigDecimal minimumNextBid,
        long bidCount,
        Instant endAt,
        int extensionCount,
        Instant closedAt,
        long lastSequenceNo,
        boolean currentUserLeading,
        boolean currentUserHasProxy,
        Instant generatedAt,
        List<Bid> bids
) {
    static AuctionRealtimeSnapshotResponse from(AuctionRealtimeSnapshotService.Snapshot source) {
        return new AuctionRealtimeSnapshotResponse(
                Long.toString(source.session().id()), source.session().status(),
                source.session().displayPrice(), source.session().minimumNextBid(), source.session().bidCount(),
                source.session().endAt(), source.session().extensionCount(), source.session().closedAt(),
                source.bids().isEmpty() ? source.session().bidCount() : source.bids().getLast().sequenceNo(),
                source.currentUserLeading(), source.currentUserHasProxy(), source.generatedAt(),
                source.bids().stream().map(Bid::from).toList()
        );
    }

    public record Bid(
            String bidId,
            BigDecimal amount,
            BigDecimal previousPrice,
            long sequenceNo,
            Instant acceptedAt
    ) {
        static Bid from(io.github.carpl2.tidebid.auction.domain.BidRecord source) {
            return new Bid(Long.toString(source.id()), source.amount(), source.previousPrice(),
                    source.sequenceNo(), source.createdAt());
        }
    }
}
