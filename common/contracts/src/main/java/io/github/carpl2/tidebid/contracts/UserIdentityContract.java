package io.github.carpl2.tidebid.contracts;

import java.util.Objects;
import java.util.Set;

/**
 * Minimal user identity that other services may consume without sharing account entities.
 */
public record UserIdentityContract(long userId, String username, Set<String> roles) {

    public UserIdentityContract {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        username = Objects.requireNonNull(username, "username must not be null");
        if (username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
    }
}
