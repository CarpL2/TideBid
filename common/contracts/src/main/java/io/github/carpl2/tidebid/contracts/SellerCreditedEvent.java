package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable confirmation that Account credited one seller exactly once.
 */
public record SellerCreditedEvent(
        SellerCreditReason creditReason,
        String creditNo,
        @JsonSerialize(using = ToStringSerializer.class) long orderId,
        String orderNo,
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        @JsonSerialize(using = ToStringSerializer.class) long sellerId,
        BigDecimal creditedAmount,
        Instant creditedAt
) {

    public static final String EVENT_TYPE = "seller.credited";
    public static final int SCHEMA_VERSION = 1;

    public SellerCreditedEvent {
        creditReason = Objects.requireNonNull(creditReason, "creditReason must not be null");
        creditNo = ContractRules.businessKey(creditNo, "creditNo");
        ContractRules.positive(orderId, "orderId");
        orderNo = ContractRules.businessKey(orderNo, "orderNo");
        ContractRules.positive(auctionId, "auctionId");
        ContractRules.positive(sellerId, "sellerId");
        creditedAmount = ContractRules.positiveMoney(creditedAmount, "creditedAmount");
        creditedAt = ContractRules.instant(creditedAt, "creditedAt");
    }
}
