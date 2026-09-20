package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record RealtimeSubscribe(
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        long lastSequenceNo
) {
    public RealtimeSubscribe {
        auctionId = ContractRules.positive(auctionId, "auctionId");
        if (lastSequenceNo < 0) {
            throw new IllegalArgumentException("lastSequenceNo must not be negative");
        }
    }
}
