package io.github.carpl2.tidebid.trade.domain;

public enum TradeOrderStatus {
    PENDING_DEPOSIT,
    PENDING_PAYMENT,
    PAYMENT_PROCESSING,
    PAID,
    PAYMENT_TIMEOUT
}
