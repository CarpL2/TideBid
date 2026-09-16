package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable timeout fact used to compensate the seller with the captured deposit only.
 */
public record OrderPaymentTimedOutEvent(
        @JsonSerialize(using = ToStringSerializer.class) long orderId,
        String orderNo,
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        @JsonSerialize(using = ToStringSerializer.class) long sellerId,
        @JsonSerialize(using = ToStringSerializer.class) long buyerId,
        BigDecimal finalPrice,
        BigDecimal capturedDepositAmount,
        BigDecimal unpaidAmount,
        Instant paymentDeadline,
        Instant timedOutAt
) {

    public static final String EVENT_TYPE = "order.payment-timed-out";
    public static final int SCHEMA_VERSION = 1;

    public OrderPaymentTimedOutEvent {
        ContractRules.positive(orderId, "orderId");
        orderNo = ContractRules.businessKey(orderNo, "orderNo");
        ContractRules.positive(auctionId, "auctionId");
        ContractRules.positive(sellerId, "sellerId");
        ContractRules.positive(buyerId, "buyerId");
        if (sellerId == buyerId) {
            throw new IllegalArgumentException("buyerId must not equal sellerId");
        }
        finalPrice = ContractRules.positiveMoney(finalPrice, "finalPrice");
        capturedDepositAmount = ContractRules.positiveMoney(capturedDepositAmount, "capturedDepositAmount");
        unpaidAmount = ContractRules.positiveMoney(unpaidAmount, "unpaidAmount");
        if (capturedDepositAmount.add(unpaidAmount).compareTo(finalPrice) != 0) {
            throw new IllegalArgumentException("capturedDepositAmount plus unpaidAmount must equal finalPrice");
        }
        paymentDeadline = ContractRules.instant(paymentDeadline, "paymentDeadline");
        timedOutAt = ContractRules.instant(timedOutAt, "timedOutAt");
        if (timedOutAt.isBefore(paymentDeadline)) {
            throw new IllegalArgumentException("timedOutAt must not be before paymentDeadline");
        }
    }
}
