package io.github.carpl2.tidebid.gateway.ratelimit;

import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;

@Component
@Profile("nacos")
public final class GatewayRateLimitWebFilter implements WebFilter, Ordered {

    private static final int ORDER_AFTER_AUTHENTICATION = Ordered.HIGHEST_PRECEDENCE + 20;

    private final AuthenticationEndpointRateLimiter rateLimiter;

    public GatewayRateLimitWebFilter(AuthenticationEndpointRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public int getOrder() {
        return ORDER_AFTER_AUTHENTICATION;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        GatewayRateLimitTarget target = target(exchange);
        if (target == null) {
            return chain.filter(exchange);
        }

        return rateLimiter.acquire(target, clientIdentifier(exchange))
                .onErrorMap(
                        exception -> !(exception instanceof BusinessException),
                        exception -> new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE)
                )
                .flatMap(decision -> {
                    if (decision.allowed()) {
                        return chain.filter(exchange);
                    }
                    exchange.getResponse().getHeaders().set(
                            HttpHeaders.RETRY_AFTER,
                            Long.toString(retryAfterSeconds(decision.retryAfter()))
                    );
                    return Mono.error(new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS));
                });
    }

    private static GatewayRateLimitTarget target(ServerWebExchange exchange) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return null;
        }
        String path = exchange.getRequest().getPath().value();
        return Arrays.stream(GatewayRateLimitTarget.values())
                .filter(candidate -> candidate.path().equals(path))
                .findFirst()
                .orElse(null);
    }

    private static String clientIdentifier(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null) {
            return "unknown";
        }
        if (remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return remoteAddress.getHostString();
    }

    private static long retryAfterSeconds(Duration retryAfter) {
        long milliseconds = Math.max(retryAfter.toMillis(), 1L);
        return Math.max((milliseconds + 999L) / 1000L, 1L);
    }
}
