package io.github.carpl2.tidebid.security;

import java.time.Duration;
import java.util.Objects;

/**
 * Public policy shared by the access-token issuer and verifiers.
 */
public record JwtTokenSettings(
        String issuer,
        String audience,
        Duration accessTokenTtl,
        Duration allowedClockSkew
) {

    public static final String DEFAULT_ISSUER = "tidebid-account";
    public static final String DEFAULT_AUDIENCE = "tidebid-api";
    public static final Duration DEFAULT_ACCESS_TOKEN_TTL = Duration.ofHours(2);
    public static final Duration DEFAULT_ALLOWED_CLOCK_SKEW = Duration.ofSeconds(30);

    public JwtTokenSettings {
        issuer = requireText(issuer, "issuer");
        audience = requireText(audience, "audience");
        accessTokenTtl = Objects.requireNonNull(accessTokenTtl, "accessTokenTtl must not be null");
        allowedClockSkew = Objects.requireNonNull(allowedClockSkew, "allowedClockSkew must not be null");
        if (accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("accessTokenTtl must be positive");
        }
        if (allowedClockSkew.isNegative()) {
            throw new IllegalArgumentException("allowedClockSkew must not be negative");
        }
        if (allowedClockSkew.compareTo(accessTokenTtl) >= 0) {
            throw new IllegalArgumentException("allowedClockSkew must be shorter than accessTokenTtl");
        }
    }

    public static JwtTokenSettings tideBidDefaults() {
        return new JwtTokenSettings(
                DEFAULT_ISSUER,
                DEFAULT_AUDIENCE,
                DEFAULT_ACCESS_TOKEN_TTL,
                DEFAULT_ALLOWED_CLOCK_SKEW
        );
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
