package io.github.carpl2.tidebid.trade.domain;

import io.github.carpl2.tidebid.contracts.AuctionClosedSoldEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** The order aggregate starts with an immutable auction result snapshot. */
public record TradeOrder(
        long id,
        String orderNo,
        long auctionId,
        long itemId,
        long winningBidId,
        long sellerId,
        long buyerId,
        String itemTitle,
        String winnerHoldNo,
        BigDecimal finalPrice,
        TradeOrderStatus status,
        SellerSettlementStatus sellerSettlementStatus,
        long version,
        Instant auctionClosedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public TradeOrder {
        if (id <= 0 || auctionId <= 0 || itemId <= 0 || winningBidId <= 0 || sellerId <= 0 || buyerId <= 0) {
            throw new IllegalArgumentException("order identifiers must be positive");
        }
        if (sellerId == buyerId) {
            throw new IllegalArgumentException("seller and buyer must differ");
        }
        orderNo = requireText(orderNo, 64, "orderNo");
        itemTitle = requireText(itemTitle, 80, "itemTitle");
        winnerHoldNo = requireText(winnerHoldNo, 64, "winnerHoldNo");
        finalPrice = requirePositiveMoney(finalPrice);
        status = Objects.requireNonNull(status, "status must not be null");
        sellerSettlementStatus = Objects.requireNonNull(
                sellerSettlementStatus, "sellerSettlementStatus must not be null");
        if (status != TradeOrderStatus.PENDING_DEPOSIT
                || sellerSettlementStatus != SellerSettlementStatus.NOT_REQUIRED
                || version != 0) {
            throw new IllegalArgumentException("a newly created order must be pending its deposit settlement");
        }
        auctionClosedAt = Objects.requireNonNull(auctionClosedAt, "auctionClosedAt must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (createdAt.isBefore(auctionClosedAt) || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("order timeline is invalid");
        }
    }

    public static TradeOrder pendingDeposit(long id, AuctionClosedSoldEvent source, Instant now) {
        return new TradeOrder(
                id,
                "TB-" + source.auctionId(),
                source.auctionId(),
                source.itemId(),
                source.winningBidId(),
                source.sellerId(),
                source.winnerId(),
                source.itemTitle(),
                source.winnerHoldNo(),
                source.finalPrice(),
                TradeOrderStatus.PENDING_DEPOSIT,
                SellerSettlementStatus.NOT_REQUIRED,
                0,
                source.closedAt(),
                now,
                now);
    }

    public boolean hasSameAuctionSnapshot(AuctionClosedSoldEvent source) {
        return auctionId == source.auctionId()
                && itemId == source.itemId()
                && winningBidId == source.winningBidId()
                && sellerId == source.sellerId()
                && buyerId == source.winnerId()
                && itemTitle.equals(source.itemTitle())
                && winnerHoldNo.equals(source.winnerHoldNo())
                && finalPrice.compareTo(source.finalPrice()) == 0
                && auctionClosedAt.equals(source.closedAt());
    }

    private static String requireText(String value, int maximum, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maximum + " characters");
        }
        return normalized;
    }

    private static BigDecimal requirePositiveMoney(BigDecimal value) {
        Objects.requireNonNull(value, "finalPrice must not be null");
        BigDecimal normalized;
        try {
            normalized = value.setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("finalPrice must have at most two decimal places", exception);
        }
        if (normalized.signum() <= 0 || normalized.precision() > 19) {
            throw new IllegalArgumentException("finalPrice is outside DECIMAL(19,2)");
        }
        return normalized;
    }
}
