package io.github.carpl2.tidebid.trade.application;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentAttemptSnapshot(
        long id,
        String paymentNo,
        long orderId,
        long buyerId,
        String requestId,
        BigDecimal amount,
        String status,
        String failureCode,
        Instant nextRecoveryAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
