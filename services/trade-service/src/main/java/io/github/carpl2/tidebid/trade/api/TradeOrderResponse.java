package io.github.carpl2.tidebid.trade.api;

import io.github.carpl2.tidebid.trade.application.TradeOrderQueryService;
import io.github.carpl2.tidebid.trade.application.TradeOrderSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

public final class TradeOrderResponse {

    private TradeOrderResponse() {
    }

    public record Detail(
            String orderId, String orderNo, String auctionId, String itemId,
            String sellerId, String buyerId, String itemTitle, String finalPrice,
            String capturedDepositAmount, String payableAmount, String status,
            Instant paymentDeadline, Instant paidAt, Instant timedOutAt,
            String sellerSettlementStatus, String sellerReceivableAmount,
            Instant sellerCreditedAt, Instant auctionClosedAt, Instant createdAt,
            Instant updatedAt, boolean paymentEligible
    ) {
        static Detail from(TradeOrderSnapshot value) {
            return new Detail(
                    Long.toString(value.id()), value.orderNo(), Long.toString(value.auctionId()),
                    Long.toString(value.itemId()), Long.toString(value.sellerId()),
                    Long.toString(value.buyerId()), value.itemTitle(), money(value.finalPrice()),
                    money(value.capturedDepositAmount()), money(value.payableAmount()), value.status(),
                    value.paymentDeadline(), value.paidAt(), value.timedOutAt(),
                    value.sellerSettlementStatus(), money(value.sellerReceivableAmount()),
                    value.sellerCreditedAt(), value.auctionClosedAt(), value.createdAt(),
                    value.updatedAt(), value.paymentEligible());
        }
    }

    public record Page(int page, int size, long total, long totalPages, List<Detail> items) {
        static Page from(TradeOrderQueryService.OrderPage value) {
            return new Page(value.page(), value.size(), value.total(), value.totalPages(),
                    value.items().stream().map(Detail::from).toList());
        }
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
