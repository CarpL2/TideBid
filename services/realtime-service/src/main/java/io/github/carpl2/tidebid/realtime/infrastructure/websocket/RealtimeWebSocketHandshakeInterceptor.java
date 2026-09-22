package io.github.carpl2.tidebid.realtime.infrastructure.websocket;

import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketApplicationService;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketIdentity;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeWebSocketProperties;
import io.github.carpl2.tidebid.realtime.infrastructure.metrics.RealtimeMetrics;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

final class RealtimeWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    static final String USER_ID_ATTRIBUTE = RealtimeWebSocketHandshakeInterceptor.class.getName() + ".userId";
    static final String ROLES_ATTRIBUTE = RealtimeWebSocketHandshakeInterceptor.class.getName() + ".roles";
    static final String ISSUED_AT_ATTRIBUTE = RealtimeWebSocketHandshakeInterceptor.class.getName() + ".issuedAt";

    private final RealtimeTicketApplicationService ticketService;
    private final RealtimeWebSocketProperties properties;
    private final RealtimeMetrics metrics;

    RealtimeWebSocketHandshakeInterceptor(
            RealtimeTicketApplicationService ticketService,
            RealtimeWebSocketProperties properties,
            RealtimeMetrics metrics
    ) {
        this.ticketService = ticketService;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        String origin = request.getHeaders().getFirst(HttpHeaders.ORIGIN);
        if (!properties.allows(origin)) {
            reject(response, HttpStatus.FORBIDDEN);
            metrics.websocketHandshakeRejected("origin");
            return false;
        }
        Optional<String> ticket = ticket(request);
        if (ticket.isEmpty()) {
            reject(response, HttpStatus.UNAUTHORIZED);
            metrics.websocketHandshakeRejected("ticket");
            return false;
        }
        try {
            Optional<RealtimeTicketIdentity> identity = ticketService.consume(ticket.get());
            if (identity.isEmpty()) {
                reject(response, HttpStatus.UNAUTHORIZED);
                metrics.websocketHandshakeRejected("ticket");
                return false;
            }
            RealtimeTicketIdentity value = identity.get();
            attributes.put(USER_ID_ATTRIBUTE, value.userId());
            attributes.put(ROLES_ATTRIBUTE, value.roles());
            attributes.put(ISSUED_AT_ATTRIBUTE, value.issuedAt());
            metrics.websocketHandshakeAccepted();
            return true;
        } catch (RuntimeException exception) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE);
            metrics.websocketHandshakeRejected("store");
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No ticket or token is copied into the handshake response.
    }

    private static Optional<String> ticket(ServerHttpRequest request) {
        List<String> values = UriComponentsBuilder.fromUri(request.getURI())
                .build(true).getQueryParams().get("ticket");
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            return Optional.empty();
        }
        String value = values.getFirst();
        if (value.length() > 256 || value.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private static void reject(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
    }
}
