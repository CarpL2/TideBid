package io.github.carpl2.tidebid.core;

/**
 * Cross-cutting errors only. Domain-specific modules define their own codes.
 */
public enum CommonErrorCode implements ErrorCode {

    INVALID_ARGUMENT("COMMON_INVALID_ARGUMENT", "Invalid request", 400),
    UNAUTHENTICATED("COMMON_UNAUTHENTICATED", "Authentication is required", 401),
    FORBIDDEN("COMMON_FORBIDDEN", "Access is forbidden", 403),
    CONFLICT("COMMON_CONFLICT", "The request conflicts with current state", 409),
    TOO_MANY_REQUESTS("COMMON_TOO_MANY_REQUESTS", "Too many requests", 429),
    INTERNAL_ERROR("COMMON_INTERNAL_ERROR", "An internal error occurred", 500),
    SERVICE_UNAVAILABLE("COMMON_SERVICE_UNAVAILABLE", "Service is temporarily unavailable", 503);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    CommonErrorCode(String code, String defaultMessage, int httpStatus) {
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
