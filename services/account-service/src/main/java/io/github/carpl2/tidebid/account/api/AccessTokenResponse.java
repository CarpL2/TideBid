package io.github.carpl2.tidebid.account.api;

import io.github.carpl2.tidebid.security.IssuedAccessToken;

import java.util.Objects;

public record AccessTokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
    private static final String BEARER = "Bearer";

    public AccessTokenResponse {
        accessToken = Objects.requireNonNull(accessToken, "accessToken must not be null");
        tokenType = Objects.requireNonNull(tokenType, "tokenType must not be null");
        if (accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be blank");
        }
        if (!BEARER.equals(tokenType)) {
            throw new IllegalArgumentException("tokenType must be Bearer");
        }
        if (expiresIn <= 0) {
            throw new IllegalArgumentException("expiresIn must be positive");
        }
    }

    static AccessTokenResponse from(IssuedAccessToken issuedToken) {
        return new AccessTokenResponse(issuedToken.value(), BEARER, issuedToken.expiresInSeconds());
    }

    @Override
    public String toString() {
        return "AccessTokenResponse[accessToken=[REDACTED], tokenType=" + tokenType
                + ", expiresIn=" + expiresIn + "]";
    }
}
