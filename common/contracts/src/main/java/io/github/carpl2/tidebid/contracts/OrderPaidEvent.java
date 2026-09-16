package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable paid-order fact. paymentNo is absent when the captured deposit covered the full price.
 */
public record OrderPaidEvent(
        @JsonSerialize(using = ToStringSerializer.class) long orderId,
        String orderNo,
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        @JsonSerialize(using = ToStringSerializer.class) long sellerId,
        @JsonSerialize(using = ToStringSerializer.class) long buyerId,
        String paymentNo,
        BigDecimal finalPrice,
        BigDecimal capturedDepositAmount,
        BigDecimal tailPaymentAmount,
        Instant paidAt
) {

    public static final String EVENT_TYPE = "order.paid";
    public static final int SCHEMA_VERSION = 1;

    public OrderPaidEvent {
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
        tailPaymentAmount = ContractRules.nonNegativeMoney(tailPaymentAmount, "tailPaymentAmount");
        if (capturedDepositAmount.add(tailPaymentAmount).compareTo(finalPrice) != 0) {
            throw new IllegalArgumentException(
                    "capturedDepositAmount plus tailPaymentAmount must equal finalPrice");
        }
        if (tailPaymentAmount.signum() == 0) {
            if (paymentNo != null) {
                throw new IllegalArgumentException("paymentNo must be absent when tailPaymentAmount is zero");
            }
        } else {
            paymentNo = ContractRules.businessKey(paymentNo, "paymentNo");
        }
        paidAt = ContractRules.instant(paidAt, "paidAt");
    }
}
