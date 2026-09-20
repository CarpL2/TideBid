package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.Objects;

public record RealtimeResyncRequired(
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        RealtimeResyncReason reason,
        long lastKnownSequenceNo
) {
    public RealtimeResyncRequired {
        auctionId = ContractRules.positive(auctionId, "auctionId");
        reason = Objects.requireNonNull(reason, "reason must not be null");
        if (lastKnownSequenceNo < 0) {
            throw new IllegalArgumentException("lastKnownSequenceNo must not be negative");
        }
    }
}
