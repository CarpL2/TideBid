package io.github.carpl2.tidebid.contracts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

final class ContractRules {

    private static final Pattern SAFE_BUSINESS_KEY = Pattern.compile("[A-Za-z0-9:_-]{1,64}");

    private ContractRules() {
    }

    static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    static BigDecimal positiveMoney(BigDecimal value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        BigDecimal normalized;
        try {
            normalized = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must have at most two decimal places", exception);
        }
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        if (normalized.precision() > 19) {
            throw new IllegalArgumentException(name + " exceeds DECIMAL(19,2)");
        }
        return normalized;
    }

    static String text(String value, int minimumLength, int maximumLength, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < minimumLength || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain " + minimumLength + " to "
                    + maximumLength + " characters");
        }
        return normalized;
    }

    static String businessKey(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (!SAFE_BUSINESS_KEY.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return normalized;
    }

    static Instant instant(Instant value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    static void closedAtOrAfterEnd(Instant endedAt, Instant closedAt) {
        if (closedAt.isBefore(endedAt)) {
            throw new IllegalArgumentException("closedAt must not be before endedAt");
        }
    }
}
