package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Requests Account to settle one auction deposit without reading Auction or Trade storage.
 */
public record DepositSettlementRequestedEvent(
        DepositSettlementType settlementType,
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        @JsonSerialize(using = ToStringSerializer.class) Long orderId,
        @JsonSerialize(using = ToStringSerializer.class) long userId,
        String holdNo,
        BigDecimal holdAmount,
        BigDecimal captureTargetAmount
) {

    public static final String EVENT_TYPE = "deposit.settlement-requested";
    public static final int SCHEMA_VERSION = 1;

    public DepositSettlementRequestedEvent {
        settlementType = Objects.requireNonNull(settlementType, "settlementType must not be null");
        ContractRules.positive(auctionId, "auctionId");
        ContractRules.positive(userId, "userId");
        holdNo = ContractRules.businessKey(holdNo, "holdNo");
        holdAmount = ContractRules.positiveMoney(holdAmount, "holdAmount");
        captureTargetAmount = ContractRules.nonNegativeMoney(captureTargetAmount, "captureTargetAmount");

        if (settlementType == DepositSettlementType.RELEASE) {
            if (orderId != null) {
                throw new IllegalArgumentException("RELEASE settlement must not contain orderId");
            }
            if (captureTargetAmount.signum() != 0) {
                throw new IllegalArgumentException("RELEASE captureTargetAmount must be zero");
            }
        } else {
            ContractRules.positive(Objects.requireNonNull(orderId, "CAPTURE orderId must not be null"), "orderId");
            if (captureTargetAmount.signum() <= 0) {
                throw new IllegalArgumentException("CAPTURE captureTargetAmount must be positive");
            }
        }
    }
}
