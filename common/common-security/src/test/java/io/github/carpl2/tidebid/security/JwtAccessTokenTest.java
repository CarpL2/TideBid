package io.github.carpl2.tidebid.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAccessTokenTest {

    private static final Instant NOW = Instant.parse("2026-08-01T01:02:03Z");
    private static RsaKeyPairMaterial keyPair;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair generated = generator.generateKeyPair();
        keyPair = new RsaKeyPairMaterial(
                (RSAPublicKey) generated.getPublic(),
                (RSAPrivateKey) generated.getPrivate()
        );
    }

    @Test
    void issuesAndVerifiesTheRequiredRs256Claims() throws Exception {
        UUID tokenId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        JwtTokenSettings settings = JwtTokenSettings.tideBidDefaults();
        Clock clock = fixedClock(NOW);
        JwtAccessTokenIssuer issuer = new JwtAccessTokenIssuer(keyPair, settings, clock, () -> tokenId);
        JwtAccessTokenVerifier verifier = new JwtAccessTokenVerifier(keyPair.publicKey(), settings, clock);
        long userIdBeyondJavaScriptSafeInteger = 9_007_199_254_740_993L;

        IssuedAccessToken token = issuer.issue(
                "alice_01",
                userIdBeyondJavaScriptSafeInteger,
                Set.of(Role.USER, Role.ADMIN)
        );
        JwtClaims verified = verifier.verify(token.value());
        SignedJWT parsed = SignedJWT.parse(token.value());

        assertThat(token.issuedAt()).isEqualTo(NOW);
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
        assertThat(token.expiresInSeconds()).isEqualTo(7200L);
        assertThat(token.toString()).doesNotContain(token.value()).contains("<redacted>");

        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(parsed.getHeader().getType()).isEqualTo(JOSEObjectType.JWT);
        assertThat(parsed.getHeader().getKeyID()).isEqualTo(RsaKeyFingerprint.keyId(keyPair.publicKey()));
        assertThat(parsed.getJWTClaimsSet().getIssuer()).isEqualTo("tidebid-account");
        assertThat(parsed.getJWTClaimsSet().getAudience()).containsExactly("tidebid-api");
        assertThat(parsed.getJWTClaimsSet().getStringClaim("userId"))
                .isEqualTo(Long.toString(userIdBeyondJavaScriptSafeInteger));
        assertThat(parsed.getJWTClaimsSet().getStringListClaim("roles"))
                .containsExactly("ADMIN", "USER");

        assertThat(verified.subject()).isEqualTo("alice_01");
        assertThat(verified.userId()).isEqualTo(userIdBeyondJavaScriptSafeInteger);
        assertThat(verified.roles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
        assertThat(verified.issuedAt()).isEqualTo(NOW);
        assertThat(verified.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
        assertThat(verified.tokenId()).isEqualTo(tokenId.toString());
    }

    @Test
    void createsANewTokenIdForEveryIssue() {
        JwtAccessTokenIssuer issuer = new JwtAccessTokenIssuer(
                keyPair,
                JwtTokenSettings.tideBidDefaults(),
                fixedClock(NOW)
        );
        JwtAccessTokenVerifier verifier = new JwtAccessTokenVerifier(
                keyPair.publicKey(),
                JwtTokenSettings.tideBidDefaults(),
                fixedClock(NOW)
        );

        String firstId = verifier.verify(issuer.issue("alice_01", 42L, Set.of(Role.USER)).value()).tokenId();
        String secondId = verifier.verify(issuer.issue("alice_01", 42L, Set.of(Role.USER)).value()).tokenId();

        assertThat(firstId).isNotEqualTo(secondId);
        assertThatCodeIsUuid(firstId);
        assertThatCodeIsUuid(secondId);
    }

    @Test
    void allowsThirtySecondsOfExpiryClockSkewAndRejectsBeyondIt() {
        JwtTokenSettings settings = JwtTokenSettings.tideBidDefaults();
        IssuedAccessToken token = new JwtAccessTokenIssuer(keyPair, settings, fixedClock(NOW))
                .issue("alice_01", 42L, Set.of(Role.USER));

        JwtAccessTokenVerifier withinSkew = new JwtAccessTokenVerifier(
                keyPair.publicKey(),
                settings,
                fixedClock(token.expiresAt().plusSeconds(29))
        );
        JwtAccessTokenVerifier beyondSkew = new JwtAccessTokenVerifier(
                keyPair.publicKey(),
                settings,
                fixedClock(token.expiresAt().plusSeconds(31))
        );

        assertThat(withinSkew.verify(token.value()).subject()).isEqualTo("alice_01");
        assertThatThrownBy(() -> beyondSkew.verify(token.value()))
                .isInstanceOf(InvalidAccessTokenException.class)
                .hasMessage("Access token is invalid");
    }

    @Test
    void rejectsATamperedSignatureWithoutEchoingTheToken() {
        JwtTokenSettings settings = JwtTokenSettings.tideBidDefaults();
        String original = new JwtAccessTokenIssuer(keyPair, settings, fixedClock(NOW))
                .issue("alice_01", 42L, Set.of(Role.USER))
                .value();
        String tampered = tamperSignature(original);
        JwtAccessTokenVerifier verifier = new JwtAccessTokenVerifier(keyPair.publicKey(), settings, fixedClock(NOW));

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(InvalidAccessTokenException.class)
                .hasMessage("Access token is invalid")
                .hasMessageNotContaining(tampered);
    }

    @Test
    void rejectsTokensSignedByAnotherKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey differentPublicKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
        JwtTokenSettings settings = JwtTokenSettings.tideBidDefaults();
        String token = new JwtAccessTokenIssuer(keyPair, settings, fixedClock(NOW))
                .issue("alice_01", 42L, Set.of(Role.USER))
                .value();

        JwtAccessTokenVerifier verifier = new JwtAccessTokenVerifier(differentPublicKey, settings, fixedClock(NOW));

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsWrongIssuerAudienceAndExcessiveLifetime() {
        JwtTokenSettings expected = JwtTokenSettings.tideBidDefaults();
        JwtAccessTokenVerifier verifier = new JwtAccessTokenVerifier(keyPair.publicKey(), expected, fixedClock(NOW));

        JwtTokenSettings wrongIssuer = new JwtTokenSettings(
                "another-issuer",
                expected.audience(),
                expected.accessTokenTtl(),
                expected.allowedClockSkew()
        );
        JwtTokenSettings wrongAudience = new JwtTokenSettings(
                expected.issuer(),
                "another-api",
                expected.accessTokenTtl(),
                expected.allowedClockSkew()
        );
        JwtTokenSettings excessiveLifetime = new JwtTokenSettings(
                expected.issuer(),
                expected.audience(),
                Duration.ofHours(3),
                expected.allowedClockSkew()
        );

        assertRejected(verifier, issueWith(wrongIssuer));
        assertRejected(verifier, issueWith(wrongAudience));
        assertRejected(verifier, issueWith(excessiveLifetime));
    }

    @Test
    void rejectsBlankTokenAndFutureIssuedAt() {
        JwtTokenSettings settings = JwtTokenSettings.tideBidDefaults();
        JwtAccessTokenVerifier verifier = new JwtAccessTokenVerifier(keyPair.publicKey(), settings, fixedClock(NOW));
        String futureToken = new JwtAccessTokenIssuer(keyPair, settings, fixedClock(NOW.plusSeconds(31)))
                .issue("alice_01", 42L, Set.of(Role.USER))
                .value();

        assertThatThrownBy(() -> verifier.verify(" ")).isInstanceOf(InvalidAccessTokenException.class);
        assertThatThrownBy(() -> verifier.verify(futureToken)).isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void rejectsMissingAndMalformedRequiredClaims() {
        JwtTokenSettings settings = JwtTokenSettings.tideBidDefaults();
        JwtAccessTokenVerifier verifier = new JwtAccessTokenVerifier(keyPair.publicKey(), settings, fixedClock(NOW));
        JwtClaimsSet missingExpiry = baseClaims(settings)
                .claim("userId", 42L)
                .claim("roles", List.of("USER"))
                .build();
        JwtClaimsSet unknownRole = baseClaims(settings)
                .expiresAt(NOW.plus(settings.accessTokenTtl()))
                .claim("userId", 42L)
                .claim("roles", List.of("SUPERUSER"))
                .build();

        assertRejected(verifier, sign(missingExpiry));
        assertRejected(verifier, sign(unknownRole));
    }

    private static String issueWith(JwtTokenSettings settings) {
        return new JwtAccessTokenIssuer(keyPair, settings, fixedClock(NOW))
                .issue("alice_01", 42L, Set.of(Role.USER))
                .value();
    }

    private static JwtClaimsSet.Builder baseClaims(JwtTokenSettings settings) {
        return JwtClaimsSet.builder()
                .issuer(settings.issuer())
                .audience(List.of(settings.audience()))
                .subject("alice_01")
                .issuedAt(NOW)
                .id("11111111-2222-3333-4444-555555555555");
    }

    private static String sign(JwtClaimsSet claims) {
        String keyId = RsaKeyFingerprint.keyId(keyPair.publicKey());
        RSAKey rsaKey = new RSAKey.Builder(keyPair.publicKey())
                .privateKey(keyPair.privateKey())
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .keyID(keyId)
                .build();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(rsaKey)));
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .type("JWT")
                .keyId(keyId)
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static void assertRejected(JwtAccessTokenVerifier verifier, String token) {
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidAccessTokenException.class);
    }

    private static void assertThatCodeIsUuid(String value) {
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static String tamperSignature(String token) {
        String[] parts = token.split("\\.");
        char replacement = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        parts[2] = replacement + parts[2].substring(1);
        return String.join(".", parts);
    }
}
