package io.github.carpl2.tidebid.trade.infrastructure.client;

import java.math.BigDecimal;
import java.time.Instant;

record AccountDebitResponse(
        String debitId, String paymentNo, String userId, String orderId,
        BigDecimal amount, String status, String failureCode, Instant decidedAt
) {
}
