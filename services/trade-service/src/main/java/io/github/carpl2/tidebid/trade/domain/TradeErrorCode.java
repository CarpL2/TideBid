package io.github.carpl2.tidebid.trade.domain;

import io.github.carpl2.tidebid.core.ErrorCode;

public enum TradeErrorCode implements ErrorCode {
    ORDER_NOT_FOUND("TRADE_ORDER_NOT_FOUND", "Order does not exist", 404),
    ORDER_FORBIDDEN("TRADE_ORDER_FORBIDDEN", "The current user cannot access this order", 403),
    ORDER_NOT_PAYABLE("TRADE_ORDER_NOT_PAYABLE", "Order is not eligible for payment", 409),
    PAYMENT_DEADLINE_EXPIRED("TRADE_PAYMENT_DEADLINE_EXPIRED", "The payment deadline has expired", 409),
    PAYMENT_IDEMPOTENCY_CONFLICT(
            "TRADE_PAYMENT_IDEMPOTENCY_CONFLICT",
            "The request ID was already used for a different payment",
            409
    ),
    PAYMENT_CONCURRENT_CONFLICT(
            "TRADE_PAYMENT_CONCURRENT_CONFLICT",
            "Another payment attempt is already processing",
            409
    );

    private final String code;
    private final String message;
    private final int status;

    TradeErrorCode(String code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    @Override public String code() { return code; }
    @Override public String defaultMessage() { return message; }
    @Override public int httpStatus() { return status; }
}
