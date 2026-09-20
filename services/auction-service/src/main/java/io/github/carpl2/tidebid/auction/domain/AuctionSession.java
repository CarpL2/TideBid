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
        Instant originalEndAt,
        int extensionCount,
        AuctionSessionStatus status,
        Long winnerId,
        Long winningBidId,
        BigDecimal finalPrice,
        Instant closedAt,
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
        originalEndAt = AuctionDomainRules.instant(originalEndAt, "originalEndAt");
        if (!originalEndAt.isAfter(startAt) || endAt.isBefore(originalEndAt)) {
            throw new IllegalArgumentException("originalEndAt must be after startAt and not after endAt");
        }
        AuctionDomainRules.nonNegative(extensionCount, "extensionCount");
        Objects.requireNonNull(status, "status must not be null");
        if (status == AuctionSessionStatus.CLOSED_SOLD) {
            AuctionDomainRules.positiveId(Objects.requireNonNull(winnerId, "winnerId"), "winnerId");
            AuctionDomainRules.positiveId(Objects.requireNonNull(winningBidId, "winningBidId"), "winningBidId");
            finalPrice = AuctionDomainRules.positiveAmount(finalPrice, "finalPrice");
            closedAt = AuctionDomainRules.instant(closedAt, "closedAt");
            if (!winnerId.equals(currentBidderId) || finalPrice.compareTo(currentPrice) != 0 || bidCount == 0) {
                throw new IllegalArgumentException("sold auction terminal snapshot must match the winning bid");
            }
        } else if (status == AuctionSessionStatus.CLOSED_UNSOLD) {
            if (winnerId != null || winningBidId != null || finalPrice != null || bidCount != 0) {
                throw new IllegalArgumentException("unsold auction must not contain a winning snapshot");
            }
            closedAt = AuctionDomainRules.instant(closedAt, "closedAt");
        } else if (winnerId != null || winningBidId != null || finalPrice != null || closedAt != null) {
            throw new IllegalArgumentException("non-terminal auction must not contain a terminal snapshot");
        }
        AuctionDomainRules.nonNegative(version, "version");
        createdAt = AuctionDomainRules.instant(createdAt, "createdAt");
        updatedAt = AuctionDomainRules.instant(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public AuctionSession(
            long id, long itemId, long sellerId, BigDecimal startPrice, BigDecimal bidIncrement,
            BigDecimal depositAmount, BigDecimal currentPrice, Long currentBidderId, long bidCount,
            Instant startAt, Instant endAt, AuctionSessionStatus status, long version,
            Instant createdAt, Instant updatedAt
    ) {
        this(id, itemId, sellerId, startPrice, bidIncrement, depositAmount, currentPrice, currentBidderId,
                bidCount, startAt, endAt, endAt, 0, status, null, null, null, null, version, createdAt, updatedAt);
    }

    public AuctionSession(
            long id, long itemId, long sellerId, BigDecimal startPrice, BigDecimal bidIncrement,
            BigDecimal depositAmount, BigDecimal currentPrice, Long currentBidderId, long bidCount,
            Instant startAt, Instant endAt, AuctionSessionStatus status, Long winnerId, Long winningBidId,
            BigDecimal finalPrice, Instant closedAt, long version, Instant createdAt, Instant updatedAt
    ) {
        this(id, itemId, sellerId, startPrice, bidIncrement, depositAmount, currentPrice, currentBidderId,
                bidCount, startAt, endAt, endAt, 0, status, winnerId, winningBidId, finalPrice, closedAt,
                version, createdAt, updatedAt);
    }

    public BigDecimal displayPrice() {
        return currentPrice == null ? startPrice : currentPrice;
    }

    public BigDecimal minimumNextBid() {
        return currentPrice == null ? startPrice : currentPrice.add(bidIncrement);
    }
}
