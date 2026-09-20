package io.github.carpl2.tidebid.contracts;

/** Stable WebSocket close statuses and deliberately non-sensitive public reasons. */
public enum RealtimeCloseCode {
    POLICY_VIOLATION(1008, "policy violation"),
    MESSAGE_TOO_LARGE(1009, "message too large"),
    INTERNAL_ERROR(1011, "internal error"),
    SERVICE_RESTART(1012, "service restart"),
    TRY_AGAIN_LATER(1013, "try again later");

    private final int statusCode;
    private final String safeReason;

    RealtimeCloseCode(int statusCode, String safeReason) {
        this.statusCode = statusCode;
        this.safeReason = safeReason;
    }

    public int statusCode() {
        return statusCode;
    }

    public String safeReason() {
        return safeReason;
    }
}
