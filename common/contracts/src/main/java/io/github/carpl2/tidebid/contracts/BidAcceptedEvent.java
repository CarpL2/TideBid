package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

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
        requirePositive(auctionId, "auctionId");
        requirePositive(bidId, "bidId");
        requirePositive(bidderId, "bidderId");
        amount = requireMoney(amount, "amount");
        requirePositive(sequenceNo, "sequenceNo");
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static BigDecimal requireMoney(BigDecimal value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        BigDecimal normalized;
        try {
            normalized = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must have at most two decimal places", exception);
        }
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        if (normalized.precision() > 19) {
            throw new IllegalArgumentException(name + " exceeds DECIMAL(19,2)");
        }
        return normalized;
    }
}
