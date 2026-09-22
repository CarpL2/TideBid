package io.github.carpl2.tidebid.realtime.infrastructure.websocket;

import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketApplicationService;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketIdentity;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketStoreUnavailableException;
import io.github.carpl2.tidebid.realtime.application.port.RealtimeConnectionLeaseStore;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeConnectionLeaseStoreUnavailableException;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeWebSocketProperties;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeProperties;
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
import java.util.UUID;

final class RealtimeWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    static final String USER_ID_ATTRIBUTE = RealtimeWebSocketHandshakeInterceptor.class.getName() + ".userId";
    static final String ROLES_ATTRIBUTE = RealtimeWebSocketHandshakeInterceptor.class.getName() + ".roles";
    static final String ISSUED_AT_ATTRIBUTE = RealtimeWebSocketHandshakeInterceptor.class.getName() + ".issuedAt";
    static final String CONNECTION_ID_ATTRIBUTE = RealtimeWebSocketHandshakeInterceptor.class.getName() + ".connectionId";
    static final String LEASE_ACQUIRED_ATTRIBUTE = RealtimeWebSocketHandshakeInterceptor.class.getName() + ".leaseAcquired";

    private final RealtimeTicketApplicationService ticketService;
    private final RealtimeWebSocketProperties properties;
    private final RealtimeMetrics metrics;
    private final RealtimeProperties realtimeProperties;
    private final RealtimeConnectionLeaseStore leaseStore;

    RealtimeWebSocketHandshakeInterceptor(
            RealtimeTicketApplicationService ticketService,
            RealtimeWebSocketProperties properties,
            RealtimeMetrics metrics,
            RealtimeProperties realtimeProperties,
            RealtimeConnectionLeaseStore leaseStore
    ) {
        this.ticketService = ticketService;
        this.properties = properties;
        this.metrics = metrics;
        this.realtimeProperties = realtimeProperties;
        this.leaseStore = leaseStore;
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
            String connectionId = UUID.randomUUID().toString();
            boolean acquired = leaseStore.acquire(
                    value.userId(), connectionId,
                    realtimeProperties.connection().maxPerUser(),
                    realtimeProperties.connection().leaseTtl());
            if (!acquired) {
                reject(response, HttpStatus.TOO_MANY_REQUESTS);
                metrics.websocketHandshakeRejected("connection_limit");
                return false;
            }
            attributes.put(USER_ID_ATTRIBUTE, value.userId());
            attributes.put(ROLES_ATTRIBUTE, value.roles());
            attributes.put(ISSUED_AT_ATTRIBUTE, value.issuedAt());
            attributes.put(CONNECTION_ID_ATTRIBUTE, connectionId);
            attributes.put(LEASE_ACQUIRED_ATTRIBUTE, Boolean.TRUE);
            metrics.websocketHandshakeAccepted();
            return true;
        } catch (RealtimeConnectionLeaseStoreUnavailableException | RealtimeTicketStoreUnavailableException exception) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE);
            metrics.websocketHandshakeRejected("store");
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // The handler releases a successful lease when the connection closes.
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
