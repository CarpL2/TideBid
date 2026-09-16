package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Requests Account to credit one seller without reading Trade storage.
 */
public record SellerCreditRequestedEvent(
        SellerCreditReason creditReason,
        String creditNo,
        @JsonSerialize(using = ToStringSerializer.class) long orderId,
        String orderNo,
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        @JsonSerialize(using = ToStringSerializer.class) long sellerId,
        BigDecimal finalPrice,
        BigDecimal capturedDepositAmount,
        BigDecimal creditAmount,
        Instant orderTerminalAt,
        Instant requestedAt
) {

    public static final String EVENT_TYPE = "seller.credit-requested";
    public static final int SCHEMA_VERSION = 1;

    public SellerCreditRequestedEvent {
        creditReason = Objects.requireNonNull(creditReason, "creditReason must not be null");
        creditNo = ContractRules.businessKey(creditNo, "creditNo");
        ContractRules.positive(orderId, "orderId");
        orderNo = ContractRules.businessKey(orderNo, "orderNo");
        ContractRules.positive(auctionId, "auctionId");
        ContractRules.positive(sellerId, "sellerId");
        finalPrice = ContractRules.positiveMoney(finalPrice, "finalPrice");
        capturedDepositAmount = ContractRules.positiveMoney(
                capturedDepositAmount, "capturedDepositAmount");
        creditAmount = ContractRules.positiveMoney(creditAmount, "creditAmount");
        orderTerminalAt = ContractRules.instant(orderTerminalAt, "orderTerminalAt");
        requestedAt = ContractRules.instant(requestedAt, "requestedAt");

        if (capturedDepositAmount.compareTo(finalPrice) > 0) {
            throw new IllegalArgumentException("capturedDepositAmount must not exceed finalPrice");
        }
        if (requestedAt.isBefore(orderTerminalAt)) {
            throw new IllegalArgumentException("requestedAt must not be before orderTerminalAt");
        }
        validateCreditAmount(creditReason, finalPrice, capturedDepositAmount, creditAmount);
    }

    private static void validateCreditAmount(
            SellerCreditReason creditReason,
            BigDecimal finalPrice,
            BigDecimal capturedDepositAmount,
            BigDecimal creditAmount
    ) {
        if (creditReason == SellerCreditReason.SALE_PROCEEDS) {
            if (creditAmount.compareTo(finalPrice) != 0) {
                throw new IllegalArgumentException("SALE_PROCEEDS creditAmount must equal finalPrice");
            }
            return;
        }
        if (capturedDepositAmount.compareTo(finalPrice) >= 0) {
            throw new IllegalArgumentException(
                    "DEFAULT_COMPENSATION requires capturedDepositAmount below finalPrice");
        }
        if (creditAmount.compareTo(capturedDepositAmount) != 0) {
            throw new IllegalArgumentException(
                    "DEFAULT_COMPENSATION creditAmount must equal capturedDepositAmount");
        }
    }
}
