package io.github.carpl2.tidebid.account.application.port;

import io.github.carpl2.tidebid.security.Role;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public interface AccountAuthenticationStore {

    Optional<StoredAccount> findByCanonicalUsername(String canonicalUsername);

    record StoredAccount(
            long userId,
            String canonicalUsername,
            String passwordHash,
            String status,
            Set<Role> roles
    ) {
        public StoredAccount {
            if (userId <= 0) {
                throw new IllegalArgumentException("userId must be positive");
            }
            canonicalUsername = requireText(canonicalUsername, "canonicalUsername");
            passwordHash = requireText(passwordHash, "passwordHash");
            status = requireText(status, "status");
            roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
        }

        @Override
        public String toString() {
            return "StoredAccount[userId=" + userId
                    + ", canonicalUsername=" + canonicalUsername
                    + ", passwordHash=[REDACTED]"
                    + ", status=" + status
                    + ", roles=" + roles + "]";
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
