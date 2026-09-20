package io.github.carpl2.tidebid.auction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

final class AuctionDomainRules {

    private static final BigDecimal MAXIMUM_AMOUNT = new BigDecimal("99999999999999999.99");
    private static final Pattern SAFE_BUSINESS_KEY = Pattern.compile("[A-Za-z0-9:_-]{1,64}");
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,48}");
    private static final Pattern LOWER_HEX_SHA256 = Pattern.compile("[0-9a-f]{64}");

    private AuctionDomainRules() {
    }

    static long positiveId(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    static int nonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    static String text(String value, int minimumLength, int maximumLength, String name) {
        String normalized = value == null ? "" : value.trim();
        int length = normalized.length();
        if (length < minimumLength || length > maximumLength) {
            throw new IllegalArgumentException(name + " must contain " + minimumLength + " to "
                    + maximumLength + " characters");
        }
        return normalized;
    }

    static String optionalText(String value, int maximumLength, String name) {
        if (value == null) {
            return null;
        }
        return text(value, 1, maximumLength, name);
    }

    static String businessKey(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (!SAFE_BUSINESS_KEY.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return normalized;
    }

    static String requestId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!SAFE_REQUEST_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("requestId has an invalid format");
        }
        return normalized;
    }

    static BigDecimal positiveAmount(BigDecimal value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.signum() <= 0 || value.scale() > 2 || value.compareTo(MAXIMUM_AMOUNT) > 0) {
            throw new IllegalArgumentException(name + " must be positive and fit DECIMAL(19,2)");
        }
        return value;
    }

    static Instant instant(Instant value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    static String optionalSha256(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (!LOWER_HEX_SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException("contentSha256 must be 64 lowercase hexadecimal characters");
        }
        return normalized;
    }

    static String sha256(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (!LOWER_HEX_SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be 64 lowercase hexadecimal characters");
        }
        return normalized;
    }
}
