package io.github.carpl2.tidebid.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;

/** Uses the same public error envelope as MVC services, including non-controller failures. */
@Component
@Order(-2)
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);
    private final ObjectMapper objectMapper;

    public GatewayErrorWebExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(exception);
        }
        String traceId = GatewayTraceWebFilter.traceId(exchange);
        int status;
        ApiResponse<Void> body;
        if (exception instanceof BusinessException business) {
            status = business.errorCode().httpStatus();
            body = ApiResponse.failure(business.errorCode(), business.getMessage(), traceId);
        } else {
            status = exception instanceof ResponseStatusException responseStatus
                    ? responseStatus.getStatusCode().value() : 500;
            if (exception instanceof ResponseStatusException responseStatus) {
                exchange.getResponse().getHeaders().putAll(responseStatus.getHeaders());
            }
            body = failure(status, traceId);
        }
        if (status >= 500) {
            log.error("Unhandled gateway failure traceId={} exceptionType={}",
                    traceId, exception.getClass().getName());
        }

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            exchange.getResponse().getHeaders().set(SecurityHeaders.TRACE_ID, traceId);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        } catch (JsonProcessingException serializationFailure) {
            return Mono.error(serializationFailure);
        }
    }

    private ApiResponse<Void> failure(int status, String traceId) {
        return Arrays.stream(CommonErrorCode.values())
                .filter(error -> error.httpStatus() == status)
                .findFirst()
                .map(error -> ApiResponse.<Void>failure(error, traceId))
                .orElseGet(() -> new ApiResponse<>("HTTP_" + status, "Request failed", null, traceId));
    }
}
