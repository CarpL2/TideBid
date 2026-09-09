package io.github.carpl2.tidebid.gateway;

import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.gateway.security.GatewayAuthenticationWebFilter;
import io.github.carpl2.tidebid.security.JwtAccessTokenIssuer;
import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.JwtTokenSettings;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.security.RsaKeyPairMaterial;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.ClassUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("standalone")
@Import(GatewayApplicationTest.ProbeConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class GatewayApplicationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private WebTestClient client;

    @Autowired
    private JwtAccessTokenIssuer tokenIssuer;

    @Test
    void servesHealthAndIdentity() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
        client.get().uri("/actuator/info").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.app.name").isEqualTo("tidebid-gateway");
        assertThat(environment.getProperty("spring.cloud.nacos.discovery.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.cloud.nacos.config.enabled", Boolean.class)).isFalse();
        assertThat(context).isInstanceOf(ReactiveWebServerApplicationContext.class);
        assertThat(ClassUtils.isPresent("org.springframework.web.servlet.DispatcherServlet",
                getClass().getClassLoader())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/beans", "/does-not-exist"})
    void rejectsUnexposedEndpointsWithJsonEvenForBrowserRequests(String path) {
        client.get().uri(path).accept(MediaType.TEXT_HTML)
                .header("X-Trace-Id", "gateway-trace-1234").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals("X-Trace-Id", "gateway-trace-1234")
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_NOT_FOUND")
                .jsonPath("$.traceId").isEqualTo("gateway-trace-1234")
                .jsonPath("$.data").isEmpty();
    }

    @Test
    void generatesOrReplacesTraceId() {
        var response = client.get().uri("/does-not-exist")
                .header("X-Trace-Id", "invalid trace").exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueMatches("X-Trace-Id", "[a-f0-9]{32}");
        String traceId = response.returnResult(String.class).getResponseHeaders().getFirst("X-Trace-Id");
        response.expectBody().jsonPath("$.traceId").isEqualTo(traceId);
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk().expectHeader().valueMatches("X-Trace-Id", "[a-f0-9]{32}");
    }

    @Test
    void mapsBusinessFailuresOutsideControllers() {
        client.get().uri("/_test/conflict").exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_CONFLICT")
                .jsonPath("$.message").isEqualTo("Conflict for test");
    }

    @Test
    void preservesFrameworkStatusWithoutExposingInternalReason() {
        client.get().uri("/_test/bad-request").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_INVALID_ARGUMENT")
                .jsonPath("$.message").isEqualTo("Invalid request");
        client.get().uri("/_test/unavailable").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_SERVICE_UNAVAILABLE");
    }

    @Test
    void hidesUnexpectedFailureDetails(CapturedOutput output) {
        client.get().uri("/_test/failure")
                .header("X-Trace-Id", "gateway-trace-5678").exchange()
                .expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_INTERNAL_ERROR")
                .jsonPath("$.message").isEqualTo("An internal error occurred")
                .jsonPath("$.traceId").isEqualTo("gateway-trace-5678");
        assertThat(output.getAll()).doesNotContain("internal-test-detail");
        assertThat(output.getAll()).contains("traceId=gateway-trace-5678");
    }

    @Test
    void preservesAllowHeaderForMethodNotAllowed() {
        client.post().uri("/_test/method").exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().valueEquals("Allow", "GET")
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_METHOD_NOT_ALLOWED");
    }

    @Test
    void authenticationFailuresUseTheGatewayJsonEnvelope() {
        client.get().uri("/api/users/me")
                .header(SecurityHeaders.INTERNAL_USER_ID, "999")
                .header(SecurityHeaders.INTERNAL_USER_ROLES, "ADMIN")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueMatches(SecurityHeaders.TRACE_ID, "[a-f0-9]{32}")
                .expectBody()
                .jsonPath("$.code").isEqualTo("COMMON_UNAUTHENTICATED")
                .jsonPath("$.message").isEqualTo("Authentication is required")
                .jsonPath("$.traceId").value(value -> assertThat(value).isInstanceOf(String.class));
    }

    @Test
    void adminPreAuthorizationRejectsAValidNormalUserToken() {
        String token = tokenIssuer.issue("gateway-user", 42L, Set.of(Role.USER)).value();

        client.get().uri("/api/admin/access-check")
                .header(SecurityHeaders.AUTHORIZATION, SecurityHeaders.BEARER_PREFIX + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_FORBIDDEN");
    }

    @Test
    void onlyTheConfiguredAuthMethodsAreAnonymous() {
        client.post().uri("/api/auth/login").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_NOT_FOUND");
        client.get().uri("/api/auth/login").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("COMMON_UNAUTHENTICATED");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        RsaKeyPairMaterial gatewayTestKeyPair() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RsaKeyPairMaterial(
                    (RSAPublicKey) pair.getPublic(),
                    (RSAPrivateKey) pair.getPrivate()
            );
        }

        @Bean
        JwtAccessTokenVerifier gatewayTestTokenVerifier(RsaKeyPairMaterial keyPair) {
            return new JwtAccessTokenVerifier(
                    keyPair.publicKey(),
                    JwtTokenSettings.tideBidDefaults(),
                    Clock.systemUTC()
            );
        }

        @Bean
        JwtAccessTokenIssuer gatewayTestTokenIssuer(RsaKeyPairMaterial keyPair) {
            return new JwtAccessTokenIssuer(
                    keyPair,
                    JwtTokenSettings.tideBidDefaults(),
                    Clock.systemUTC()
            );
        }

        @Bean
        GatewayAuthenticationWebFilter gatewayAuthenticationWebFilter(JwtAccessTokenVerifier verifier) {
            return new GatewayAuthenticationWebFilter(verifier);
        }

        // Test-only filter verifies errors before any route/controller is selected.
        @Bean
        @Order(-100)
        WebFilter failureProbe() {
            return (exchange, chain) -> switch (exchange.getRequest().getPath().value()) {
                case "/_test/conflict" -> Mono.error(
                        new BusinessException(CommonErrorCode.CONFLICT, "Conflict for test"));
                case "/_test/bad-request" -> Mono.error(
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "internal-test-detail"));
                case "/_test/unavailable" -> Mono.error(
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "internal-test-detail"));
                case "/_test/failure" -> Mono.error(new IllegalStateException("internal-test-detail"));
                case "/_test/method" -> Mono.error(
                        new MethodNotAllowedException(HttpMethod.POST, List.of(HttpMethod.GET)));
                default -> chain.filter(exchange);
            };
        }
    }
}
