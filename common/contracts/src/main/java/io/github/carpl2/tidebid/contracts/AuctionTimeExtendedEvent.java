package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

/**
 * Public event emitted when anti-sniping rules move an auction deadline.
 */
public record AuctionTimeExtendedEvent(
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        Instant previousEndAt,
        Instant endAt,
        int extensionCount,
        Instant extendedAt
) {

    public static final String EVENT_TYPE = "auction.time-extended";
    public static final int SCHEMA_VERSION = 1;

    public AuctionTimeExtendedEvent {
        auctionId = ContractRules.positive(auctionId, "auctionId");
        previousEndAt = ContractRules.instant(previousEndAt, "previousEndAt");
        endAt = ContractRules.instant(endAt, "endAt");
        extendedAt = ContractRules.instant(extendedAt, "extendedAt");
        if (!endAt.isAfter(previousEndAt)) {
            throw new IllegalArgumentException("endAt must be after previousEndAt");
        }
        if (extensionCount <= 0) {
            throw new IllegalArgumentException("extensionCount must be positive");
        }
        if (extendedAt.isAfter(endAt)) {
            throw new IllegalArgumentException("extendedAt must not be after endAt");
        }
    }
}
