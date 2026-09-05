package io.github.carpl2.tidebid.core;

/**
 * Stable error information shared by protocol adapters and business code.
 */
public interface ErrorCode {

    String code();

    String defaultMessage();

    int httpStatus();
}
