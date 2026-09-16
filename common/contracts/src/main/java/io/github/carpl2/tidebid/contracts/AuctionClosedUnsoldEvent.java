package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.Instant;

/**
 * Immutable unsold-auction snapshot. Winner, bid and price fields are absent by construction.
 */
public record AuctionClosedUnsoldEvent(
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        @JsonSerialize(using = ToStringSerializer.class) long itemId,
        String itemTitle,
        @JsonSerialize(using = ToStringSerializer.class) long sellerId,
        Instant endedAt,
        Instant closedAt
) {

    public static final String EVENT_TYPE = "auction.closed-unsold";
    public static final int SCHEMA_VERSION = 1;

    public AuctionClosedUnsoldEvent {
        ContractRules.positive(auctionId, "auctionId");
        ContractRules.positive(itemId, "itemId");
        itemTitle = ContractRules.text(itemTitle, 2, 80, "itemTitle");
        ContractRules.positive(sellerId, "sellerId");
        endedAt = ContractRules.instant(endedAt, "endedAt");
        closedAt = ContractRules.instant(closedAt, "closedAt");
        ContractRules.closedAtOrAfterEnd(endedAt, closedAt);
    }
}
