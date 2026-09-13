package io.github.carpl2.tidebid.auction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record AuctionSession(
        long id,
        long itemId,
        long sellerId,
        BigDecimal startPrice,
        BigDecimal bidIncrement,
        BigDecimal depositAmount,
        BigDecimal currentPrice,
        Long currentBidderId,
        long bidCount,
        Instant startAt,
        Instant endAt,
        AuctionSessionStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public AuctionSession {
        AuctionDomainRules.positiveId(id, "id");
        AuctionDomainRules.positiveId(itemId, "itemId");
        AuctionDomainRules.positiveId(sellerId, "sellerId");
        startPrice = AuctionDomainRules.positiveAmount(startPrice, "startPrice");
        bidIncrement = AuctionDomainRules.positiveAmount(bidIncrement, "bidIncrement");
        depositAmount = AuctionDomainRules.positiveAmount(depositAmount, "depositAmount");
        AuctionDomainRules.nonNegative(bidCount, "bidCount");
        if (bidCount == 0 && (currentPrice != null || currentBidderId != null)) {
            throw new IllegalArgumentException("empty auction must not have a current bid");
        }
        if (bidCount > 0) {
            currentPrice = AuctionDomainRules.positiveAmount(currentPrice, "currentPrice");
            AuctionDomainRules.positiveId(Objects.requireNonNull(currentBidderId, "currentBidderId"), "currentBidderId");
            if (currentPrice.compareTo(startPrice) < 0) {
                throw new IllegalArgumentException("currentPrice must not be below startPrice");
            }
        }
        startAt = AuctionDomainRules.instant(startAt, "startAt");
        endAt = AuctionDomainRules.instant(endAt, "endAt");
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("endAt must be after startAt");
        }
        Objects.requireNonNull(status, "status must not be null");
        AuctionDomainRules.nonNegative(version, "version");
        createdAt = AuctionDomainRules.instant(createdAt, "createdAt");
        updatedAt = AuctionDomainRules.instant(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public BigDecimal displayPrice() {
        return currentPrice == null ? startPrice : currentPrice;
    }

    public BigDecimal minimumNextBid() {
        return currentPrice == null ? startPrice : currentPrice.add(bidIncrement);
    }
}
