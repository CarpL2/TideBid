package io.github.carpl2.tidebid.contracts;

import java.util.Objects;

public record RealtimeError(RealtimeErrorCode code, String message, boolean recoverable) {
    public RealtimeError {
        code = Objects.requireNonNull(code, "code must not be null");
        message = ContractRules.text(message, 1, 256, "message");
    }
}
