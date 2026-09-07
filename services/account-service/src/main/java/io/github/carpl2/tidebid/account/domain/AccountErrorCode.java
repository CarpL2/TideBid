package io.github.carpl2.tidebid.account.domain;

import io.github.carpl2.tidebid.core.ErrorCode;

public enum AccountErrorCode implements ErrorCode {

    USERNAME_ALREADY_EXISTS("ACCOUNT_USERNAME_ALREADY_EXISTS", "Username already exists", 409);

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
