package io.github.carpl2.tidebid.trade.api;

import io.github.carpl2.tidebid.trade.application.PaymentAttemptSnapshot;

import java.math.BigDecimal;
import java.time.Instant;

public record TradePaymentResponse(
        String paymentAttemptId,
        String paymentNo,
        String orderId,
        BigDecimal amount,
        String status,
        String failureCode,
        Instant nextRecoveryAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
    static TradePaymentResponse from(PaymentAttemptSnapshot value) {
        return new TradePaymentResponse(
                Long.toString(value.id()), value.paymentNo(), Long.toString(value.orderId()),
                value.amount(), value.status(), value.failureCode(), value.nextRecoveryAt(),
                value.completedAt(), value.createdAt(), value.updatedAt());
    }
}
