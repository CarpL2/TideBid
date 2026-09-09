package io.github.carpl2.tidebid.gateway.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.gateway.server.webflux.globalcors.add-to-simple-url-handler-mapping=true",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-origins[0]=http://localhost:5173",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-origins[1]=http://127.0.0.1:5173",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-methods[0]=GET",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-methods[1]=POST",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-methods[2]=OPTIONS",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-headers[0]=Authorization",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-headers[1]=Content-Type",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-headers[2]=X-Request-Id",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-headers[3]=X-Trace-Id",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].exposed-headers[0]=X-Trace-Id",
                "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allow-credentials=false"
        }
)
@ActiveProfiles("standalone")
@Import(GatewayRateLimitApplicationTest.ProbeConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayRateLimitApplicationTest {

    @Autowired
    private WebTestClient client;

    @Autowired
    private ControllableRateLimiter rateLimiter;

    @BeforeEach
    void allowRequests() {
        rateLimiter.allow();
    }

    @Test
    void rejectedRequestUsesUniformJsonAndRetryAfter() {
        rateLimiter.rejectFor(Duration.ofMillis(1_001));

        client.post().uri("/api/auth/login").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "2")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueMatches("X-Trace-Id", "[a-f0-9]{32}")
                .expectBody()
                .jsonPath("$.code").isEqualTo("COMMON_TOO_MANY_REQUESTS")
                .jsonPath("$.message").isEqualTo("Too many requests")
                .jsonPath("$.traceId").value(value -> assertThat(value).isInstanceOf(String.class));
    }

    @Test
    void redisFailureUsesUniformServiceUnavailableResponse() {
        rateLimiter.fail();

        client.post().uri("/api/auth/register").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("COMMON_SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Service is temporarily unavailable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://127.0.0.1:5173"})
    void allowsOnlyConfiguredLocalViteOrigins(String origin) {
        client.options().uri("/api/auth/login")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type,X-Request-Id,X-Trace-Id")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin)
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        value -> assertThat(value).contains("POST"))
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        value -> assertThat(value.toLowerCase()).contains("x-request-id", "x-trace-id"));
    }

    @Test
    void rejectsUnlistedCorsOriginWithoutAllowHeaders() {
        client.options().uri("/api/auth/login")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        ControllableRateLimiter controllableRateLimiter() {
            return new ControllableRateLimiter();
        }

        @Bean
        GatewayRateLimitWebFilter gatewayRateLimitWebFilter(ControllableRateLimiter rateLimiter) {
            return new GatewayRateLimitWebFilter(rateLimiter);
        }
    }

    static final class ControllableRateLimiter implements AuthenticationEndpointRateLimiter {

        private final AtomicReference<Mono<RateLimitDecision>> next =
                new AtomicReference<>(Mono.just(RateLimitDecision.allow()));

        void allow() {
            next.set(Mono.just(RateLimitDecision.allow()));
        }

        void rejectFor(Duration retryAfter) {
            next.set(Mono.just(RateLimitDecision.reject(retryAfter)));
        }

        void fail() {
            next.set(Mono.error(new IllegalStateException("redis connection detail")));
        }

        @Override
        public Mono<RateLimitDecision> acquire(GatewayRateLimitTarget target, String clientIdentifier) {
            return next.get();
        }
    }
}
