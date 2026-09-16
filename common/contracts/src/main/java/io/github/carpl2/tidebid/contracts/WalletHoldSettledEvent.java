package io.github.carpl2.tidebid.contracts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable result of settling one wallet hold.
 */
public record WalletHoldSettledEvent(
        DepositSettlementType settlementType,
        WalletHoldSettlementStatus holdStatus,
        @JsonSerialize(using = ToStringSerializer.class) long auctionId,
        @JsonSerialize(using = ToStringSerializer.class) Long orderId,
        @JsonSerialize(using = ToStringSerializer.class) long userId,
        String holdNo,
        BigDecimal holdAmount,
        BigDecimal captureTargetAmount,
        BigDecimal capturedAmount,
        BigDecimal releasedAmount,
        Instant settledAt
) {

    public static final String EVENT_TYPE = "wallet-hold.settled";
    public static final int SCHEMA_VERSION = 1;

    public WalletHoldSettledEvent {
        settlementType = Objects.requireNonNull(settlementType, "settlementType must not be null");
        holdStatus = Objects.requireNonNull(holdStatus, "holdStatus must not be null");
        ContractRules.positive(auctionId, "auctionId");
        ContractRules.positive(userId, "userId");
        holdNo = ContractRules.businessKey(holdNo, "holdNo");
        holdAmount = ContractRules.positiveMoney(holdAmount, "holdAmount");
        captureTargetAmount = ContractRules.nonNegativeMoney(captureTargetAmount, "captureTargetAmount");
        capturedAmount = ContractRules.nonNegativeMoney(capturedAmount, "capturedAmount");
        releasedAmount = ContractRules.nonNegativeMoney(releasedAmount, "releasedAmount");
        settledAt = ContractRules.instant(settledAt, "settledAt");

        if (capturedAmount.add(releasedAmount).compareTo(holdAmount) != 0) {
            throw new IllegalArgumentException("capturedAmount plus releasedAmount must equal holdAmount");
        }
        if (settlementType == DepositSettlementType.RELEASE) {
            validateRelease(orderId, holdStatus, holdAmount, captureTargetAmount, capturedAmount, releasedAmount);
        } else {
            validateCapture(orderId, holdStatus, holdAmount, captureTargetAmount, capturedAmount, releasedAmount);
        }
    }

    private static void validateRelease(
            Long orderId,
            WalletHoldSettlementStatus holdStatus,
            BigDecimal holdAmount,
            BigDecimal captureTargetAmount,
            BigDecimal capturedAmount,
            BigDecimal releasedAmount
    ) {
        if (orderId != null) {
            throw new IllegalArgumentException("RELEASE settlement must not contain orderId");
        }
        if (holdStatus != WalletHoldSettlementStatus.RELEASED) {
            throw new IllegalArgumentException("RELEASE settlement requires RELEASED holdStatus");
        }
        if (captureTargetAmount.signum() != 0
                || capturedAmount.signum() != 0
                || releasedAmount.compareTo(holdAmount) != 0) {
            throw new IllegalArgumentException("RELEASE settlement must release the complete hold amount");
        }
    }

    private static void validateCapture(
            Long orderId,
            WalletHoldSettlementStatus holdStatus,
            BigDecimal holdAmount,
            BigDecimal captureTargetAmount,
            BigDecimal capturedAmount,
            BigDecimal releasedAmount
    ) {
        ContractRules.positive(Objects.requireNonNull(orderId, "CAPTURE orderId must not be null"), "orderId");
        if (holdStatus != WalletHoldSettlementStatus.CAPTURED) {
            throw new IllegalArgumentException("CAPTURE settlement requires CAPTURED holdStatus");
        }
        if (captureTargetAmount.signum() <= 0) {
            throw new IllegalArgumentException("CAPTURE captureTargetAmount must be positive");
        }
        BigDecimal expectedCaptured = holdAmount.min(captureTargetAmount);
        BigDecimal expectedReleased = holdAmount.subtract(expectedCaptured);
        if (capturedAmount.compareTo(expectedCaptured) != 0
                || releasedAmount.compareTo(expectedReleased) != 0) {
            throw new IllegalArgumentException("CAPTURE amounts must use min(holdAmount, captureTargetAmount)");
        }
    }
}
