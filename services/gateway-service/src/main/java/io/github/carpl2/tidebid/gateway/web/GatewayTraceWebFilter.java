package io.github.carpl2.tidebid.gateway.web;

import io.github.carpl2.tidebid.core.TraceIds;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
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
    private static final Logger log = LoggerFactory.getLogger(GatewayTraceWebFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = traceId(exchange);
        exchange.getResponse().getHeaders().set(SecurityHeaders.TRACE_ID, traceId);
        exchange.getResponse().beforeCommit(() -> {
            // A downstream service also emits this header. The gateway is the public response
            // boundary, so overwrite any forwarded copy and expose exactly one trusted value.
            exchange.getResponse().getHeaders().set(SecurityHeaders.TRACE_ID, traceId);
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            int statusCode = status == null ? 200 : status.value();
            log.info(
                    "Gateway request completed traceId={} method={} path={} status={}",
                    traceId,
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(),
                    statusCode
            );
            return Mono.empty();
        });
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
