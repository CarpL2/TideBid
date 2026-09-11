package io.github.carpl2.tidebid.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies RS256 signatures and TideBid's required access-token claims.
 */
public final class JwtAccessTokenVerifier {

    private final NimbusJwtDecoder decoder;
    private final JwtTokenSettings settings;
    private final Clock clock;
    private final String keyId;

    public JwtAccessTokenVerifier(RSAPublicKey publicKey, JwtTokenSettings settings, Clock clock) {
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.keyId = RsaKeyFingerprint.keyId(publicKey);

        this.decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(settings.allowedClockSkew());
        timestampValidator.setClock(clock);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud",
                audience -> audience != null && audience.contains(settings.audience())
        );
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestampValidator,
                new JwtIssuerValidator(settings.issuer()),
                audienceValidator
        ));
    }

    public JwtClaims verify(String encodedToken) {
        if (encodedToken == null || encodedToken.isBlank()) {
            throw new InvalidAccessTokenException();
        }
        try {
            Jwt jwt = decoder.decode(encodedToken);
            return toDomainClaims(jwt);
        } catch (JwtException | IllegalArgumentException | ClassCastException exception) {
            throw new InvalidAccessTokenException();
        }
    }

    private JwtClaims toDomainClaims(Jwt jwt) {
        requireExpectedKeyId(jwt);
        String subject = requireText(jwt.getSubject(), "sub");
        long userId = requirePositiveIntegralUserId(jwt.getClaim("userId"));
        Set<Role> roles = requireRoles(jwt.getClaim("roles"));
        Instant issuedAt = requireInstant(jwt.getIssuedAt(), "iat");
        Instant expiresAt = requireInstant(jwt.getExpiresAt(), "exp");
        String tokenId = requireText(jwt.getId(), "jti");

        Duration lifetime = Duration.between(issuedAt, expiresAt);
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(settings.accessTokenTtl()) > 0) {
            throw new IllegalArgumentException("access token lifetime exceeds policy");
        }
        if (issuedAt.isAfter(clock.instant().plus(settings.allowedClockSkew()))) {
            throw new IllegalArgumentException("access token was issued in the future");
        }
        return new JwtClaims(subject, userId, roles, issuedAt, expiresAt, tokenId);
    }

    private void requireExpectedKeyId(Jwt jwt) {
        Object actualKeyId = jwt.getHeaders().get("kid");
        if (!(actualKeyId instanceof String value) || !keyId.equals(value)) {
            throw new IllegalArgumentException("access token key identifier is invalid");
        }
    }

    private static long requirePositiveIntegralUserId(Object value) {
        long userId;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            userId = ((Number) value).longValue();
        } else if (value instanceof BigInteger integer && integer.bitLength() < Long.SIZE) {
            userId = integer.longValue();
        } else if (value instanceof String text && text.matches("[1-9][0-9]*")) {
            try {
                userId = Long.parseLong(text);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("userId claim must fit in a signed 64-bit integer", exception);
            }
        } else {
            throw new IllegalArgumentException("userId claim must be an integer");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId claim must be positive");
        }
        return userId;
    }

    private static Set<Role> requireRoles(Object value) {
        if (!(value instanceof Collection<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("roles claim must be a non-empty array");
        }
        Set<Role> roles = new LinkedHashSet<>();
        for (Object item : values) {
            if (!(item instanceof String roleName) || roleName.isBlank()) {
                throw new IllegalArgumentException("roles claim contains an invalid role");
            }
            roles.add(Role.valueOf(roleName));
        }
        return Set.copyOf(roles);
    }

    private static String requireText(String value, String claimName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(claimName + " claim is required");
        }
        return value;
    }

    private static Instant requireInstant(Instant value, String claimName) {
        if (value == null) {
            throw new IllegalArgumentException(claimName + " claim is required");
        }
        return value;
    }
}
