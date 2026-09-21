package io.github.carpl2.tidebid.realtime.application.service;

import io.github.carpl2.tidebid.security.Role;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record RealtimeTicketIdentity(long userId, Set<Role> roles, Instant issuedAt) {

    public RealtimeTicketIdentity {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("roles must not be empty");
        }
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
    }
}
