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
                source.session().bidCount(),
                source.currentUserLeading(), source.currentUserHasProxy(), source.generatedAt(),
                source.bids().stream().map(bid -> Bid.from(bid, source.trustedUserId())).toList()
        );
    }

    public record Bid(
            String bidId,
            BigDecimal amount,
            BigDecimal previousPrice,
            long sequenceNo,
            boolean mine,
            Instant acceptedAt
    ) {
        static Bid from(io.github.carpl2.tidebid.auction.domain.BidRecord source, Long trustedUserId) {
            return new Bid(Long.toString(source.id()), source.amount(), source.previousPrice(),
                    source.sequenceNo(), trustedUserId != null && source.bidderId() == trustedUserId,
                    source.createdAt());
        }
    }
}
