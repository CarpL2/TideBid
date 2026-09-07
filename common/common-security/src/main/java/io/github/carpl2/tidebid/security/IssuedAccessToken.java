package io.github.carpl2.tidebid.security;

import java.time.Instant;
import java.util.Objects;

/**
 * A signed access token and its public timing metadata.
 */
public record IssuedAccessToken(String value, Instant issuedAt, Instant expiresAt) {

    public IssuedAccessToken {
        value = Objects.requireNonNull(value, "value must not be null");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    public long expiresInSeconds() {
        return expiresAt.getEpochSecond() - issuedAt.getEpochSecond();
    }

    @Override
    public String toString() {
        return "IssuedAccessToken[value=<redacted>, issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + "]";
    }
}
