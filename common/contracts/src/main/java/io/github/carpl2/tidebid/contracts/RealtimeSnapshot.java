package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RealtimeSnapshot(
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        RealtimeAuctionStatus status,
        BigDecimal displayPrice,
        BigDecimal minimumNextBid,
        long bidCount,
        Instant endAt,
        Instant closedAt,
        int extensionCount,
        long lastSequenceNo,
        boolean leading,
        boolean proxyActive,
        List<RealtimeBidView> bids
) {
    public RealtimeSnapshot {
        auctionId = ContractRules.positive(auctionId, "auctionId");
        status = Objects.requireNonNull(status, "status must not be null");
        displayPrice = ContractRules.nonNegativeMoney(displayPrice, "displayPrice");
        minimumNextBid = ContractRules.positiveMoney(minimumNextBid, "minimumNextBid");
        if (bidCount < 0 || lastSequenceNo < 0 || extensionCount < 0) {
            throw new IllegalArgumentException("counts and sequence numbers must not be negative");
        }
        endAt = ContractRules.instant(endAt, "endAt");
        boolean terminal = status == RealtimeAuctionStatus.CLOSED_SOLD
                || status == RealtimeAuctionStatus.CLOSED_UNSOLD;
        if (terminal) {
            closedAt = ContractRules.instant(closedAt, "closedAt");
            ContractRules.closedAtOrAfterEnd(endAt, closedAt);
        } else if (closedAt != null) {
            throw new IllegalArgumentException("closedAt must be null before auction closes");
        }
        bids = List.copyOf(Objects.requireNonNull(bids, "bids must not be null"));
        long previous = 0;
        for (RealtimeBidView bid : bids) {
            Objects.requireNonNull(bid, "bids must not contain null");
            if (bid.sequenceNo() <= previous || bid.sequenceNo() > lastSequenceNo) {
                throw new IllegalArgumentException("bids must be ordered and not exceed lastSequenceNo");
            }
            previous = bid.sequenceNo();
        }
    }
}
