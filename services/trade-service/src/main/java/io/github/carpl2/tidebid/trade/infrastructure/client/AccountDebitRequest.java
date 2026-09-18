package io.github.carpl2.tidebid.trade.infrastructure.client;

import java.math.BigDecimal;

record AccountDebitRequest(String paymentNo, String userId, String orderId, BigDecimal amount) {
}
