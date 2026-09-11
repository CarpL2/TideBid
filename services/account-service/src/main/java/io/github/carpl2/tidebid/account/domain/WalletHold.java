package io.github.carpl2.tidebid.account.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record WalletHold(
        long id,
        String holdNo,
        long userId,
        WalletHoldBusinessType businessType,
        BigDecimal amount,
        WalletHoldStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    private static final Pattern HOLD_NO_PATTERN = Pattern.compile("[A-Za-z0-9:_-]{1,64}");
    private static final BigDecimal MAXIMUM_AMOUNT = new BigDecimal("99999999999999999.99");

    public WalletHold {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        holdNo = requireHoldNo(holdNo);
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        Objects.requireNonNull(businessType, "businessType must not be null");
        amount = requireAmount(amount);
        Objects.requireNonNull(status, "status must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public static String requireHoldNo(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!HOLD_NO_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("holdNo must contain 1 to 64 safe characters");
        }
        return normalized;
    }

    public static BigDecimal requireAmount(BigDecimal value) {
        Objects.requireNonNull(value, "amount must not be null");
        if (value.signum() <= 0 || value.scale() > 2 || value.compareTo(MAXIMUM_AMOUNT) > 0) {
            throw new IllegalArgumentException("amount must be positive and fit DECIMAL(19,2)");
        }
        return value;
    }
}
