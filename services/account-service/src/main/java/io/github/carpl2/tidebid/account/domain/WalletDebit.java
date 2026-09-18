package io.github.carpl2.tidebid.account.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record WalletDebit(
        long id,
        String paymentNo,
        long userId,
        long orderId,
        BigDecimal amount,
        WalletDebitStatus status,
        String failureCode,
        Instant decidedAt,
        Instant createdAt
) {
    private static final Pattern BUSINESS_NO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:_-]{0,63}");
    private static final BigDecimal MAX_MONEY = new BigDecimal("99999999999999999.99");

    public static final String INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";

    public WalletDebit {
        if (id <= 0 || userId <= 0 || orderId <= 0) {
            throw new IllegalArgumentException("wallet debit IDs must be positive");
        }
        paymentNo = requirePaymentNo(paymentNo);
        amount = requireAmount(amount);
        status = Objects.requireNonNull(status, "status must not be null");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (decidedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("decidedAt must not be before createdAt");
        }
        if (status == WalletDebitStatus.SUCCEEDED && failureCode != null) {
            throw new IllegalArgumentException("successful wallet debit must not have a failureCode");
        }
        if (status == WalletDebitStatus.REJECTED && !INSUFFICIENT_BALANCE.equals(failureCode)) {
            throw new IllegalArgumentException("rejected wallet debit must have a supported failureCode");
        }
    }

    public static String requirePaymentNo(String value) {
        if (value == null || !BUSINESS_NO.matcher(value).matches()) {
            throw new IllegalArgumentException("paymentNo has an invalid format");
        }
        return value;
    }

    public static BigDecimal requireAmount(BigDecimal value) {
        Objects.requireNonNull(value, "amount must not be null");
        BigDecimal normalized;
        try {
            normalized = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("amount must have at most two decimal places", exception);
        }
        if (normalized.signum() <= 0 || normalized.compareTo(MAX_MONEY) > 0) {
            throw new IllegalArgumentException("amount must be positive and fit DECIMAL(19,2)");
        }
        return normalized;
    }
}
