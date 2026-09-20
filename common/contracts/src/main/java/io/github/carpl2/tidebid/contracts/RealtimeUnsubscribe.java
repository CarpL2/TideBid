package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record RealtimeUnsubscribe(
        @JsonSerialize(using = ToStringSerializer.class) long auctionId
) {
    public RealtimeUnsubscribe {
        auctionId = ContractRules.positive(auctionId, "auctionId");
    }
}
