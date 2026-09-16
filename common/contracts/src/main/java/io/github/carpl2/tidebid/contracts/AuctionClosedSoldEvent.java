package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable sold-auction snapshot used by Trade without reading Auction's database.
 */
public record AuctionClosedSoldEvent(
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        @JsonSerialize(using = ToStringSerializer.class) long itemId,
        String itemTitle,
        @JsonSerialize(using = ToStringSerializer.class) long sellerId,
        @JsonSerialize(using = ToStringSerializer.class) long winnerId,
        @JsonSerialize(using = ToStringSerializer.class) long winningBidId,
        String winnerHoldNo,
        BigDecimal depositAmount,
        BigDecimal finalPrice,
        Instant endedAt,
        Instant closedAt
) {

    public static final String EVENT_TYPE = "auction.closed-sold";
    public static final int SCHEMA_VERSION = 1;

    public AuctionClosedSoldEvent {
        ContractRules.positive(auctionId, "auctionId");
        ContractRules.positive(itemId, "itemId");
        itemTitle = ContractRules.text(itemTitle, 2, 80, "itemTitle");
        ContractRules.positive(sellerId, "sellerId");
        ContractRules.positive(winnerId, "winnerId");
        ContractRules.positive(winningBidId, "winningBidId");
        if (sellerId == winnerId) {
            throw new IllegalArgumentException("winnerId must not equal sellerId");
        }
        winnerHoldNo = ContractRules.businessKey(winnerHoldNo, "winnerHoldNo");
        depositAmount = ContractRules.positiveMoney(depositAmount, "depositAmount");
        finalPrice = ContractRules.positiveMoney(finalPrice, "finalPrice");
        endedAt = ContractRules.instant(endedAt, "endedAt");
        closedAt = ContractRules.instant(closedAt, "closedAt");
        ContractRules.closedAtOrAfterEnd(endedAt, closedAt);
    }
}
