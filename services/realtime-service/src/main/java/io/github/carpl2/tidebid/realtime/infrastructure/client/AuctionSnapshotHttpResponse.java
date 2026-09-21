package io.github.carpl2.tidebid.realtime.infrastructure.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

record AuctionSnapshotHttpResponse(
        String auctionId,
        String status,
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
    record Bid(
            String bidId,
            BigDecimal amount,
            BigDecimal previousPrice,
            long sequenceNo,
            boolean mine,
            Instant acceptedAt
    ) { }
}
