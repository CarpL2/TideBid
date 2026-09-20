package io.github.carpl2.tidebid.auction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record AuctionProxyBid(
        long id,
        long auctionId,
        long bidderId,
        BigDecimal maxAmount,
        AuctionProxyBidStatus status,
        long priority,
        long version,
        Instant disabledAt,
        Instant createdAt,
        Instant updatedAt
) {
    public AuctionProxyBid {
        AuctionDomainRules.positiveId(id, "id");
        AuctionDomainRules.positiveId(auctionId, "auctionId");
        AuctionDomainRules.positiveId(bidderId, "bidderId");
        maxAmount = AuctionDomainRules.positiveAmount(maxAmount, "maxAmount");
        status = Objects.requireNonNull(status, "status must not be null");
        AuctionDomainRules.positiveId(priority, "priority");
        AuctionDomainRules.nonNegative(version, "version");
        createdAt = AuctionDomainRules.instant(createdAt, "createdAt");
        updatedAt = AuctionDomainRules.instant(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (status == AuctionProxyBidStatus.ACTIVE && disabledAt != null) {
            throw new IllegalArgumentException("active proxy bid must not have disabledAt");
        }
        if (status == AuctionProxyBidStatus.DISABLED) {
            disabledAt = AuctionDomainRules.instant(disabledAt, "disabledAt");
            if (disabledAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("disabledAt must not be before createdAt");
            }
        }
    }
}
