package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RealtimeAuctionClosed(
        UUID eventId,
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        RealtimeAuctionStatus status,
        BigDecimal finalPrice,
        boolean wonByCurrentUser,
        Instant closedAt
) {
    public RealtimeAuctionClosed {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        auctionId = ContractRules.positive(auctionId, "auctionId");
        status = Objects.requireNonNull(status, "status must not be null");
        closedAt = ContractRules.instant(closedAt, "closedAt");
        if (status == RealtimeAuctionStatus.CLOSED_SOLD) {
            finalPrice = ContractRules.positiveMoney(finalPrice, "finalPrice");
        } else if (status == RealtimeAuctionStatus.CLOSED_UNSOLD) {
            if (finalPrice != null || wonByCurrentUser) {
                throw new IllegalArgumentException("unsold auction must not have a final price or winner");
            }
        } else {
            throw new IllegalArgumentException("status must be a closed auction status");
        }
    }
}
