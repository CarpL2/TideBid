package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable fact emitted after an accepted bid and its session CAS update commit together.
 */
public record BidAcceptedEvent(
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        @JsonSerialize(using = ToStringSerializer.class) long bidId,
        @JsonSerialize(using = ToStringSerializer.class) long bidderId,
        BigDecimal amount,
        long sequenceNo,
        Instant acceptedAt
) {

    public static final String EVENT_TYPE = "auction.bid-accepted";
    public static final int SCHEMA_VERSION = 1;

    public BidAcceptedEvent {
        ContractRules.positive(auctionId, "auctionId");
        ContractRules.positive(bidId, "bidId");
        ContractRules.positive(bidderId, "bidderId");
        amount = ContractRules.positiveMoney(amount, "amount");
        ContractRules.positive(sequenceNo, "sequenceNo");
        acceptedAt = ContractRules.instant(acceptedAt, "acceptedAt");
    }
}
