package io.github.carpl2.tidebid.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Issues TideBid access tokens. Only the account service should construct this component.
 */
public final class JwtAccessTokenIssuer {

    private final JwtEncoder encoder;
    private final JwtTokenSettings settings;
    private final Clock clock;
    private final Supplier<UUID> tokenIdSupplier;
    private final String keyId;

    public JwtAccessTokenIssuer(RsaKeyPairMaterial keyPair, JwtTokenSettings settings, Clock clock) {
        this(keyPair, settings, clock, UUID::randomUUID);
    }

    JwtAccessTokenIssuer(
            RsaKeyPairMaterial keyPair,
            JwtTokenSettings settings,
            Clock clock,
            Supplier<UUID> tokenIdSupplier
    ) {
        Objects.requireNonNull(keyPair, "keyPair must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.tokenIdSupplier = Objects.requireNonNull(tokenIdSupplier, "tokenIdSupplier must not be null");
        this.keyId = RsaKeyFingerprint.keyId(keyPair.publicKey());

        RSAKey rsaKey = new RSAKey.Builder(keyPair.publicKey())
                .privateKey(keyPair.privateKey())
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .keyID(keyId)
                .build();
        ImmutableJWKSet<SecurityContext> keySource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        this.encoder = new NimbusJwtEncoder(keySource);
    }

    public IssuedAccessToken issue(String subject, long userId, Set<Role> roles) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(settings.accessTokenTtl());
        String tokenId = Objects.requireNonNull(tokenIdSupplier.get(), "tokenIdSupplier returned null").toString();
        JwtClaims domainClaims = new JwtClaims(subject, userId, roles, issuedAt, expiresAt, tokenId);

        List<String> roleNames = domainClaims.roles().stream()
                .map(Role::name)
                .sorted(Comparator.naturalOrder())
                .toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(settings.issuer())
                .audience(List.of(settings.audience()))
                .subject(domainClaims.subject())
                .issuedAt(domainClaims.issuedAt())
                .expiresAt(domainClaims.expiresAt())
                .id(domainClaims.tokenId())
                .claim("userId", Long.toString(domainClaims.userId()))
                .claim("roles", roleNames)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .type("JWT")
                .keyId(keyId)
                .build();

        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, issuedAt, expiresAt);
    }
}
