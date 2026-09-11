package io.github.carpl2.tidebid.account.domain;

import io.github.carpl2.tidebid.core.ErrorCode;

public enum AccountErrorCode implements ErrorCode {

    USERNAME_ALREADY_EXISTS("ACCOUNT_USERNAME_ALREADY_EXISTS", "Username already exists", 409),
    INVALID_CREDENTIALS("ACCOUNT_INVALID_CREDENTIALS", "Invalid username or password", 401),
    ACCOUNT_DISABLED("ACCOUNT_DISABLED", "Account is disabled", 403),
    ACCOUNT_NOT_FOUND("ACCOUNT_NOT_FOUND", "Account does not exist", 404),
    WALLET_NOT_FOUND("ACCOUNT_WALLET_NOT_FOUND", "Wallet does not exist", 404),
    WALLET_INSUFFICIENT_BALANCE(
            "ACCOUNT_WALLET_INSUFFICIENT_BALANCE",
            "Available wallet balance is insufficient",
            409
    ),
    WALLET_HOLD_IDEMPOTENCY_CONFLICT(
            "ACCOUNT_WALLET_HOLD_IDEMPOTENCY_CONFLICT",
            "Wallet hold number was already used with a different payload",
            409
    );

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    AccountErrorCode(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
