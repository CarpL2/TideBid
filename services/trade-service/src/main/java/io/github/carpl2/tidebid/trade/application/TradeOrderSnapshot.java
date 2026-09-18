package io.github.carpl2.tidebid.trade.application;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeOrderSnapshot(
        long id,
        String orderNo,
        long auctionId,
        long itemId,
        long sellerId,
        long buyerId,
        String itemTitle,
        BigDecimal finalPrice,
        BigDecimal capturedDepositAmount,
        BigDecimal payableAmount,
        String status,
        Instant paymentDeadline,
        Instant paidAt,
        Instant timedOutAt,
        String sellerSettlementStatus,
        BigDecimal sellerReceivableAmount,
        Instant sellerCreditedAt,
        Instant auctionClosedAt,
        Instant createdAt,
        Instant updatedAt,
        boolean paymentEligible
) {
}
