package io.github.carpl2.tidebid.security;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable authenticated identity exposed to application code.
 */
public record AuthenticatedUser(long userId, String subject, Set<Role> roles) {

    public AuthenticatedUser {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        subject = Objects.requireNonNull(subject, "subject must not be null");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
