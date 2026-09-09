package io.github.carpl2.tidebid.gateway.ratelimit;

import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class GatewayRateLimitWebFilterTest {

    @Test
    void allowedLoginUsesTheTcpPeerAndContinuesTheChain() {
        AtomicReference<GatewayRateLimitTarget> target = new AtomicReference<>();
        AtomicReference<String> client = new AtomicReference<>();
        GatewayRateLimitWebFilter filter = new GatewayRateLimitWebFilter((actualTarget, actualClient) -> {
            target.set(actualTarget);
            client.set(actualClient);
            return Mono.just(RateLimitDecision.allow());
        });
        MockServerWebExchange exchange = exchange("/api/auth/login", "192.0.2.10");
        AtomicInteger chainCalls = new AtomicInteger();

        filter.filter(exchange, ignored -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        }).block();

        assertThat(target.get()).isEqualTo(GatewayRateLimitTarget.LOGIN);
        assertThat(client.get()).isEqualTo("192.0.2.10");
        assertThat(chainCalls).hasValue(1);
    }

    @Test
    void rejectedRegistrationReturnsRateLimitErrorAndRoundedRetryAfter() {
        GatewayRateLimitWebFilter filter = new GatewayRateLimitWebFilter(
                (target, client) -> Mono.just(RateLimitDecision.reject(Duration.ofMillis(1_001)))
        );
        MockServerWebExchange exchange = exchange("/api/auth/register", "192.0.2.11");

        Throwable thrown = catchThrowable(
                () -> filter.filter(exchange, ignored -> Mono.empty()).block()
        );

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).errorCode()).isEqualTo(CommonErrorCode.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
    }

    @Test
    void redisFailureBecomesServiceUnavailableWithoutCallingTheChain() {
        GatewayRateLimitWebFilter filter = new GatewayRateLimitWebFilter(
                (target, client) -> Mono.error(new IllegalStateException("redis connection detail"))
        );
        MockServerWebExchange exchange = exchange("/api/auth/login", "192.0.2.12");
        AtomicInteger chainCalls = new AtomicInteger();

        Throwable thrown = catchThrowable(
                () -> filter.filter(exchange, ignored -> {
                    chainCalls.incrementAndGet();
                    return Mono.empty();
                }).block()
        );

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).errorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
        assertThat(thrown).hasMessage("Service is temporarily unavailable");
        assertThat(chainCalls).hasValue(0);
    }

    @Test
    void nonPostAndOrdinaryBusinessRequestsDoNotTouchTheLimiter() {
        AtomicInteger limiterCalls = new AtomicInteger();
        GatewayRateLimitWebFilter filter = new GatewayRateLimitWebFilter((target, client) -> {
            limiterCalls.incrementAndGet();
            return Mono.just(RateLimitDecision.reject(Duration.ofSeconds(10)));
        });
        AtomicInteger chainCalls = new AtomicInteger();

        filter.filter(
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/auth/login").build()),
                ignored -> {
                    chainCalls.incrementAndGet();
                    return Mono.empty();
                }
        ).block();
        filter.filter(
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/users/me").build()),
                ignored -> {
                    chainCalls.incrementAndGet();
                    return Mono.empty();
                }
        ).block();

        assertThat(limiterCalls).hasValue(0);
        assertThat(chainCalls).hasValue(2);
    }

    private static MockServerWebExchange exchange(String path, String address) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post(path)
                        .remoteAddress(new InetSocketAddress(address, 43210))
                        .build()
        );
    }
}
