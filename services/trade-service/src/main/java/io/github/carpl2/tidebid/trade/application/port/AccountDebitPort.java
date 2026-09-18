package io.github.carpl2.tidebid.trade.application.port;

import io.github.carpl2.tidebid.core.TraceIds;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public interface AccountDebitPort {

    DebitResult debit(DebitCommand command);

    DebitLookup lookup(DebitLookupQuery query);

    record DebitCommand(
            String paymentNo, long buyerId, long orderId, BigDecimal amount,
            String requestId, String traceId
    ) {
        private static final Pattern BUSINESS_NO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:_-]{0,63}");
        private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,48}");

        public DebitCommand {
            if (paymentNo == null || !BUSINESS_NO.matcher(paymentNo).matches()) {
                throw new IllegalArgumentException("paymentNo has an invalid format");
            }
            if (buyerId <= 0 || orderId <= 0) {
                throw new IllegalArgumentException("buyerId and orderId must be positive");
            }
            amount = Objects.requireNonNull(amount, "amount must not be null");
            if (amount.signum() <= 0 || amount.scale() > 2 || amount.precision() > 19) {
                throw new IllegalArgumentException("amount must fit positive DECIMAL(19,2)");
            }
            if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
                throw new IllegalArgumentException("requestId has an invalid format");
            }
            if (!TraceIds.isValid(traceId)) {
                throw new IllegalArgumentException("traceId has an invalid format");
            }
        }
    }

    record DebitLookupQuery(String paymentNo, String traceId) {
        private static final Pattern BUSINESS_NO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:_-]{0,63}");

        public DebitLookupQuery {
            if (paymentNo == null || !BUSINESS_NO.matcher(paymentNo).matches()) {
                throw new IllegalArgumentException("paymentNo has an invalid format");
            }
            if (!TraceIds.isValid(traceId)) {
                throw new IllegalArgumentException("traceId has an invalid format");
            }
        }
    }

    sealed interface DebitResult permits Succeeded, Rejected, Unknown {
    }

    record Succeeded(String paymentNo, long buyerId, long orderId, BigDecimal amount, Instant decidedAt)
            implements DebitResult {
    }

    record Rejected(
            String paymentNo, long buyerId, long orderId, BigDecimal amount,
            String failureCode, Instant decidedAt
    ) implements DebitResult {
    }

    record Unknown() implements DebitResult {
    }

    sealed interface DebitLookup permits Found, Missing, LookupUnknown {
    }

    record Found(DebitResult result) implements DebitLookup {
        public Found {
            if (!(result instanceof Succeeded) && !(result instanceof Rejected)) {
                throw new IllegalArgumentException("lookup result must be definite");
            }
        }
    }

    record Missing() implements DebitLookup {
    }

    record LookupUnknown() implements DebitLookup {
    }
}
