package io.github.carpl2.tidebid.gateway.web;

import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Trace state belongs to the exchange, not to a thread-local in WebFlux. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayTraceWebFilter implements WebFilter {

    public static final String TRACE_ID_ATTRIBUTE = GatewayTraceWebFilter.class.getName() + ".traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = traceId(exchange);
        exchange.getResponse().getHeaders().set(SecurityHeaders.TRACE_ID, traceId);
        ServerWebExchange tracedExchange = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(SecurityHeaders.TRACE_ID, traceId)))
                .build();
        return chain.filter(tracedExchange);
    }

    static String traceId(ServerWebExchange exchange) {
        String existing = exchange.getAttribute(TRACE_ID_ATTRIBUTE);
        if (TraceIds.isValid(existing)) {
            return existing;
        }
        String traceId = TraceIds.resolve(exchange.getRequest().getHeaders().getFirst(SecurityHeaders.TRACE_ID));
        exchange.getAttributes().put(TRACE_ID_ATTRIBUTE, traceId);
        return traceId;
    }
}
