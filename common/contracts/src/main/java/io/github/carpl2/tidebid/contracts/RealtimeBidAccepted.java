package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RealtimeBidAccepted(
        UUID eventId,
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        @JsonSerialize(using = ToStringSerializer.class) long bidId,
        BigDecimal amount,
        long sequenceNo,
        boolean mine,
        Instant acceptedAt
) {
    public RealtimeBidAccepted {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        auctionId = ContractRules.positive(auctionId, "auctionId");
        bidId = ContractRules.positive(bidId, "bidId");
        amount = ContractRules.positiveMoney(amount, "amount");
        if (sequenceNo <= 0) {
            throw new IllegalArgumentException("sequenceNo must be positive");
        }
        acceptedAt = ContractRules.instant(acceptedAt, "acceptedAt");
    }
}
