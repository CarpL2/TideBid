package io.github.carpl2.tidebid.core;

import java.util.Objects;

/**
 * Public HTTP response envelope used by TideBid services.
 */
public record ApiResponse<T>(String code, String message, T data, String traceId) {

    public static final String SUCCESS_CODE = "SUCCESS";
    public static final String SUCCESS_MESSAGE = "OK";

    public ApiResponse {
        code = Objects.requireNonNull(code, "code must not be null");
        message = Objects.requireNonNull(message, "message must not be null");
        traceId = Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data, traceId);
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message, String traceId) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        return new ApiResponse<>(errorCode.code(), message, null, traceId);
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String traceId) {
        return failure(errorCode, errorCode.defaultMessage(), traceId);
    }
}
