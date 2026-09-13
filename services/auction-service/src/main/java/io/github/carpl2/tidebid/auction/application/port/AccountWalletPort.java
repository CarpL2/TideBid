package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.core.TraceIds;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public interface AccountWalletPort {

    HoldAttempt hold(HoldCommand command);

    HoldLookup findByHoldNo(String holdNo, String traceId);

    static String requireHoldNo(String holdNo) {
        return requireMatching(holdNo, Pattern.compile("[A-Za-z0-9:_-]{1,64}"), "holdNo");
    }

    static String requireTraceId(String traceId) {
        if (!TraceIds.isValid(traceId)) {
            throw new IllegalArgumentException("traceId has an invalid format");
        }
        return traceId;
    }

    static String requireRequestId(String requestId) {
        return requireMatching(requestId, HoldCommand.REQUEST_ID_PATTERN, "requestId");
    }

    record HoldCommand(
            String holdNo,
            long userId,
            BigDecimal amount,
            String requestId,
            String traceId
    ) {
        private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,48}");
        private static final BigDecimal MAXIMUM_AMOUNT = new BigDecimal("99999999999999999.99");

        public HoldCommand {
            holdNo = requireHoldNo(holdNo);
            if (userId <= 0) {
                throw new IllegalArgumentException("userId must be positive");
            }
            amount = Objects.requireNonNull(amount, "amount must not be null");
            if (amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(MAXIMUM_AMOUNT) > 0) {
                throw new IllegalArgumentException("amount must be positive and fit DECIMAL(19,2)");
            }
            requestId = requireRequestId(requestId);
            traceId = requireTraceId(traceId);
        }
    }

    record HoldSnapshot(
            long holdId,
            String holdNo,
            long userId,
            BigDecimal amount,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        public HoldSnapshot {
            if (holdId <= 0 || userId <= 0) {
                throw new IllegalArgumentException("holdId and userId must be positive");
            }
            Objects.requireNonNull(holdNo, "holdNo must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        }
    }

    sealed interface HoldAttempt permits Held, Rejected, Unknown {
    }

    record Held(HoldSnapshot hold) implements HoldAttempt {
        public Held {
            Objects.requireNonNull(hold, "hold must not be null");
        }
    }

    record Rejected(String errorCode) implements HoldAttempt {
        public Rejected {
            errorCode = requireErrorCode(errorCode);
        }
    }

    record Unknown(String errorCode) implements HoldAttempt, HoldLookup {
        public Unknown {
            errorCode = requireErrorCode(errorCode);
        }
    }

    sealed interface HoldLookup permits Found, Missing, Unknown {
    }

    record Found(HoldSnapshot hold) implements HoldLookup {
        public Found {
            Objects.requireNonNull(hold, "hold must not be null");
        }
    }

    record Missing() implements HoldLookup {
    }

    private static String requireErrorCode(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        return normalized;
    }

    private static String requireMatching(String value, Pattern pattern, String name) {
        String normalized = value == null ? "" : value.trim();
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return normalized;
    }
}
