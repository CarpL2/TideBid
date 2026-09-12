package io.github.carpl2.tidebid.auction.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record BidRecord(
        long id,
        long auctionId,
        long bidderId,
        String requestId,
        BigDecimal amount,
        BigDecimal previousPrice,
        long sequenceNo,
        Instant createdAt
) {
    public BidRecord {
        AuctionDomainRules.positiveId(id, "id");
        AuctionDomainRules.positiveId(auctionId, "auctionId");
        AuctionDomainRules.positiveId(bidderId, "bidderId");
        requestId = AuctionDomainRules.requestId(requestId);
        amount = AuctionDomainRules.positiveAmount(amount, "amount");
        if (sequenceNo <= 0) {
            throw new IllegalArgumentException("sequenceNo must be positive");
        }
        if (sequenceNo == 1 && previousPrice != null) {
            throw new IllegalArgumentException("first bid must not have previousPrice");
        }
        if (sequenceNo > 1) {
            previousPrice = AuctionDomainRules.positiveAmount(previousPrice, "previousPrice");
            if (amount.compareTo(previousPrice) <= 0) {
                throw new IllegalArgumentException("amount must be greater than previousPrice");
            }
        }
        createdAt = AuctionDomainRules.instant(createdAt, "createdAt");
    }
}
