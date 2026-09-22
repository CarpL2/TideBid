package io.github.carpl2.tidebid.gateway.security;

import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.security.InvalidAccessTokenException;
import io.github.carpl2.tidebid.security.JwtAccessTokenVerifier;
import io.github.carpl2.tidebid.security.JwtClaims;
import io.github.carpl2.tidebid.security.Role;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Establishes trusted gateway-to-service identity headers from a verified access token. */
@Component
@Profile("nacos")
public final class GatewayAuthenticationWebFilter implements WebFilter, Ordered {

    private static final int ORDER_AFTER_TRACE = Ordered.HIGHEST_PRECEDENCE + 10;
    private static final List<String> UNTRUSTED_FORWARDING_HEADERS = List.of(
            "Forwarded",
            "X-Forwarded-For",
            "X-Forwarded-Host",
            "X-Forwarded-Port",
            "X-Forwarded-Proto",
            "X-Forwarded-Prefix"
    );

    private final JwtAccessTokenVerifier tokenVerifier;

    public GatewayAuthenticationWebFilter(JwtAccessTokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    public int getOrder() {
        return ORDER_AFTER_TRACE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerWebExchange sanitizedExchange = withoutUntrustedBoundaryHeaders(exchange);
        if (isProtocolPreflight(sanitizedExchange) || isAnonymousEndpoint(sanitizedExchange)
                || isWebSocketEndpoint(sanitizedExchange)) {
            return chain.filter(isWebSocketEndpoint(sanitizedExchange)
                    ? withoutWebSocketAuthorization(sanitizedExchange)
                    : sanitizedExchange);
        }
        if (!isProtectedBusinessPath(sanitizedExchange)) {
            return chain.filter(sanitizedExchange);
        }

        JwtClaims claims = verify(bearerToken(sanitizedExchange));
        if (isAdminPath(sanitizedExchange) && !claims.roles().contains(Role.ADMIN)) {
            return Mono.error(new BusinessException(CommonErrorCode.FORBIDDEN));
        }

        String roles = claims.roles().stream()
                .map(Role::name)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(","));
        ServerWebExchange authenticatedExchange = sanitizedExchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.set(SecurityHeaders.INTERNAL_USER_ID, Long.toString(claims.userId()));
                    headers.set(SecurityHeaders.INTERNAL_USER_ROLES, roles);
                }))
                .build();
        return chain.filter(authenticatedExchange);
    }

    private JwtClaims verify(String encodedToken) {
        try {
            return tokenVerifier.verify(encodedToken);
        } catch (InvalidAccessTokenException exception) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
    }

    private static String bearerToken(ServerWebExchange exchange) {
        List<String> values = exchange.getRequest().getHeaders().getOrEmpty(SecurityHeaders.AUTHORIZATION);
        if (values.size() != 1) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        String authorization = values.getFirst();
        if (!authorization.startsWith(SecurityHeaders.BEARER_PREFIX)) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        String encodedToken = authorization.substring(SecurityHeaders.BEARER_PREFIX.length());
        if (encodedToken.isBlank() || encodedToken.chars().anyMatch(Character::isWhitespace)) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        return encodedToken;
    }

    private static ServerWebExchange withoutUntrustedBoundaryHeaders(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.remove(SecurityHeaders.INTERNAL_USER_ID);
                    headers.remove(SecurityHeaders.INTERNAL_USER_ROLES);
                    headers.remove(SecurityHeaders.INTERNAL_SERVICE_TOKEN);
                    UNTRUSTED_FORWARDING_HEADERS.forEach(headers::remove);
                }))
                .build();
    }

    private static ServerWebExchange withoutWebSocketAuthorization(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> headers.remove(SecurityHeaders.AUTHORIZATION)))
                .build();
    }

    private static boolean isProtocolPreflight(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() == HttpMethod.OPTIONS;
    }

    private static boolean isAnonymousEndpoint(ServerWebExchange exchange) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return false;
        }
        String path = exchange.getRequest().getPath().value();
        return path.equals("/api/auth/register") || path.equals("/api/auth/login");
    }

    private static boolean isProtectedBusinessPath(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return path.equals("/api") || path.startsWith("/api/")
                || path.equals("/ws") || path.startsWith("/ws/");
    }

    private static boolean isWebSocketEndpoint(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value().equals("/ws/auctions");
    }

    private static boolean isAdminPath(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return path.equals("/api/admin") || path.startsWith("/api/admin/");
    }
}
