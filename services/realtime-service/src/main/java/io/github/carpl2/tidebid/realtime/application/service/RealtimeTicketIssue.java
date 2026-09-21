package io.github.carpl2.tidebid.realtime.application.service;

import java.time.Instant;
import java.util.Objects;

public record RealtimeTicketIssue(String ticket, Instant expiresAt) {

    public RealtimeTicketIssue {
        ticket = Objects.requireNonNull(ticket, "ticket must not be null");
        if (ticket.isBlank()) {
            throw new IllegalArgumentException("ticket must not be blank");
        }
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    @Override
    public String toString() {
        return "RealtimeTicketIssue[ticket=[REDACTED], expiresAt=" + expiresAt + "]";
    }
}
