package io.github.carpl2.tidebid.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Claims that every TideBid access token must contain.
 */
public record JwtClaims(
        String subject,
        long userId,
        Set<Role> roles,
        Instant issuedAt,
        Instant expiresAt,
        String tokenId
) {

    public JwtClaims {
        subject = Objects.requireNonNull(subject, "subject must not be null");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        tokenId = Objects.requireNonNull(tokenId, "tokenId must not be null");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        if (tokenId.isBlank()) {
            throw new IllegalArgumentException("tokenId must not be blank");
        }
    }
}
