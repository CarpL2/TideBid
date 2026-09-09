package io.github.carpl2.tidebid.gateway.security;

import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.security.JwtAccessTokenIssuer;
import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.JwtTokenSettings;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.security.RsaKeyPairMaterial;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class GatewayAuthenticationWebFilterTest {

    private static final Instant NOW = Instant.parse("2026-09-09T08:00:00Z");
    private static RsaKeyPairMaterial keyPair;
    private static GatewayAuthenticationWebFilter filter;
    private static JwtAccessTokenIssuer issuer;

    @BeforeAll
    static void setUpKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair generated = generator.generateKeyPair();
        keyPair = new RsaKeyPairMaterial(
                (RSAPublicKey) generated.getPublic(),
                (RSAPrivateKey) generated.getPrivate()
        );
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        issuer = new JwtAccessTokenIssuer(keyPair, JwtTokenSettings.tideBidDefaults(), clock);
        filter = new GatewayAuthenticationWebFilter(
                new JwtAccessTokenVerifier(keyPair.publicKey(), JwtTokenSettings.tideBidDefaults(), clock)
        );
    }

    @Test
    void anonymousRegistrationDoesNotRequireTokenAndStripsForgedIdentity() {
        MockServerWebExchange input = exchange(
                MockServerHttpRequest.post("/api/auth/register")
                        .header(SecurityHeaders.INTERNAL_USER_ID, "999")
                        .header(SecurityHeaders.INTERNAL_USER_ROLES, "ADMIN")
        );

        ServerWebExchange forwarded = forwardedExchange(input);

        assertThat(forwarded.getRequest().getHeaders().containsKey(SecurityHeaders.INTERNAL_USER_ID)).isFalse();
        assertThat(forwarded.getRequest().getHeaders().containsKey(SecurityHeaders.INTERNAL_USER_ROLES)).isFalse();
    }

    @Test
    void verifiedTokenReplacesForgedIdentityWithStableTrustedHeaders() {
        String token = issuer.issue("alice", 42L, Set.of(Role.USER, Role.ADMIN)).value();
        MockServerWebExchange input = exchange(
                MockServerHttpRequest.get("/api/users/me")
                        .header(SecurityHeaders.AUTHORIZATION, SecurityHeaders.BEARER_PREFIX + token)
                        .header(SecurityHeaders.INTERNAL_USER_ID, "999")
                        .header(SecurityHeaders.INTERNAL_USER_ROLES, "ROOT")
        );

        ServerWebExchange forwarded = forwardedExchange(input);

        assertThat(forwarded.getRequest().getHeaders().getFirst(SecurityHeaders.INTERNAL_USER_ID))
                .isEqualTo("42");
        assertThat(forwarded.getRequest().getHeaders().getFirst(SecurityHeaders.INTERNAL_USER_ROLES))
                .isEqualTo("ADMIN,USER");
        assertThat(forwarded.getRequest().getHeaders().getFirst(SecurityHeaders.AUTHORIZATION))
                .isEqualTo(SecurityHeaders.BEARER_PREFIX + token);
    }

    @Test
    void normalUserCannotEnterAdminRoute() {
        String token = issuer.issue("alice", 42L, Set.of(Role.USER)).value();
        MockServerWebExchange input = exchange(
                MockServerHttpRequest.get("/api/admin/access-check")
                        .header(SecurityHeaders.AUTHORIZATION, SecurityHeaders.BEARER_PREFIX + token)
        );

        assertBusinessFailure(input, CommonErrorCode.FORBIDDEN);
    }

    @Test
    void unknownNonBusinessPathRemainsAvailableToRoutingAndStillStripsIdentity() {
        MockServerWebExchange input = exchange(
                MockServerHttpRequest.get("/does-not-exist")
                        .header(SecurityHeaders.INTERNAL_USER_ID, "999")
                        .header(SecurityHeaders.INTERNAL_USER_ROLES, "ADMIN")
        );

        ServerWebExchange forwarded = forwardedExchange(input);

        assertThat(forwarded.getRequest().getHeaders().containsKey(SecurityHeaders.INTERNAL_USER_ID)).isFalse();
        assertThat(forwarded.getRequest().getHeaders().containsKey(SecurityHeaders.INTERNAL_USER_ROLES)).isFalse();
    }

    @Test
    void optionsPreflightDoesNotRequireToken() {
        MockServerWebExchange input = exchange(
                MockServerHttpRequest.method(HttpMethod.OPTIONS, "/api/users/me")
                        .header(SecurityHeaders.INTERNAL_USER_ROLES, "ADMIN")
        );

        ServerWebExchange forwarded = forwardedExchange(input);

        assertThat(forwarded.getRequest().getHeaders().containsKey(SecurityHeaders.INTERNAL_USER_ROLES)).isFalse();
    }

    @Test
    void onlyPostLoginAndRegistrationAreAnonymous() {
        assertBusinessFailure(
                exchange(MockServerHttpRequest.get("/api/auth/login")),
                CommonErrorCode.UNAUTHENTICATED
        );
    }

    @Test
    void multipleAuthorizationHeadersAreRejected() {
        String token = issuer.issue("alice", 42L, Set.of(Role.USER)).value();
        MockServerWebExchange input = exchange(
                MockServerHttpRequest.get("/api/users/me")
                        .header(
                                SecurityHeaders.AUTHORIZATION,
                                SecurityHeaders.BEARER_PREFIX + token,
                                SecurityHeaders.BEARER_PREFIX + token
                        )
        );

        assertBusinessFailure(input, CommonErrorCode.UNAUTHENTICATED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic abc", "Bearer ", "Bearer abc def", "Bearer malformed"})
    void malformedAuthorizationIsRejected(String authorization) {
        MockServerWebExchange input = exchange(
                MockServerHttpRequest.get("/api/users/me")
                        .header(SecurityHeaders.AUTHORIZATION, authorization)
        );

        assertBusinessFailure(input, CommonErrorCode.UNAUTHENTICATED);
    }

    @Test
    void missingTamperedAndExpiredTokensAreRejected() {
        assertBusinessFailure(
                exchange(MockServerHttpRequest.get("/api/users/me")),
                CommonErrorCode.UNAUTHENTICATED
        );

        String validToken = issuer.issue("alice", 42L, Set.of(Role.USER)).value();
        assertBusinessFailure(
                exchange(MockServerHttpRequest.get("/api/users/me")
                        .header(SecurityHeaders.AUTHORIZATION, SecurityHeaders.BEARER_PREFIX + validToken + "x")),
                CommonErrorCode.UNAUTHENTICATED
        );

        Clock oldClock = Clock.fixed(NOW.minusSeconds(3 * 60 * 60), ZoneOffset.UTC);
        String expiredToken = new JwtAccessTokenIssuer(
                keyPair,
                JwtTokenSettings.tideBidDefaults(),
                oldClock
        ).issue("alice", 42L, Set.of(Role.USER)).value();
        assertBusinessFailure(
                exchange(MockServerHttpRequest.get("/api/users/me")
                        .header(SecurityHeaders.AUTHORIZATION, SecurityHeaders.BEARER_PREFIX + expiredToken)),
                CommonErrorCode.UNAUTHENTICATED
        );
    }

    private static MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        return MockServerWebExchange.from(request.build());
    }

    private static ServerWebExchange forwardedExchange(MockServerWebExchange input) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(input, exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        }).block();
        assertThat(forwarded.get()).isNotNull();
        return forwarded.get();
    }

    private static void assertBusinessFailure(
            MockServerWebExchange input,
            CommonErrorCode expectedError
    ) {
        Throwable thrown = catchThrowable(() -> filter.filter(input, exchange -> Mono.empty()).block());
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).errorCode()).isEqualTo(expectedError);
    }
}
