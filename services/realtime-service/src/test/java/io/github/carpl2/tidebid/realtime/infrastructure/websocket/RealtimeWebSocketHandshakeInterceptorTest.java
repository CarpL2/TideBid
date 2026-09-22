package io.github.carpl2.tidebid.realtime.infrastructure.websocket;

import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketApplicationService;
import io.github.carpl2.tidebid.realtime.application.service.RealtimeTicketIdentity;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeWebSocketProperties;
import io.github.carpl2.tidebid.realtime.infrastructure.metrics.RealtimeMetrics;
import io.github.carpl2.tidebid.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.socket.WebSocketHandler;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeWebSocketHandshakeInterceptorTest {

    @Mock
    private RealtimeTicketApplicationService ticketService;

    private RealtimeWebSocketHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new RealtimeWebSocketHandshakeInterceptor(
                ticketService,
                new RealtimeWebSocketProperties(true, List.of("http://localhost:5173")),
                new RealtimeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @Test
    void consumesTicketAndBindsOnlyMinimumIdentity() throws Exception {
        Instant issuedAt = Instant.parse("2026-09-22T07:00:00Z");
        RealtimeTicketIdentity identity = new RealtimeTicketIdentity(42L, Set.of(Role.USER), issuedAt);
        when(ticketService.consume("ticket-value")).thenReturn(Optional.of(identity));
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request("ticket-value", "http://localhost:5173"),
                response(), (WebSocketHandler) null, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(RealtimeWebSocketHandshakeInterceptor.USER_ID_ATTRIBUTE, 42L)
                .containsEntry(RealtimeWebSocketHandshakeInterceptor.ROLES_ATTRIBUTE, Set.of(Role.USER))
                .containsEntry(RealtimeWebSocketHandshakeInterceptor.ISSUED_AT_ATTRIBUTE, issuedAt)
                .hasSize(3);
    }

    @Test
    void rejectsMissingOrForeignOriginWithoutConsumingTicket() throws Exception {
        ServletServerHttpResponse response = response();
        boolean accepted = interceptor.beforeHandshake(request("ticket-value", null), response,
                (WebSocketHandler) null, new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(response.getServletResponse().getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        org.mockito.Mockito.verifyNoInteractions(ticketService);
    }

    @Test
    void rejectsReplayedOrMissingTicketWithoutEchoingIt() throws Exception {
        when(ticketService.consume("ticket-value")).thenReturn(Optional.empty());
        ServletServerHttpResponse response = response();
        boolean accepted = interceptor.beforeHandshake(request("ticket-value", "http://localhost:5173"), response,
                (WebSocketHandler) null, new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(response.getServletResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(((MockHttpServletResponse) response.getServletResponse()).getContentAsString())
                .doesNotContain("ticket-value");
    }

    private static ServerHttpRequest request(String ticket, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/auctions");
        request.setQueryString("ticket=" + ticket);
        if (origin != null) request.addHeader("Origin", origin);
        return new ServletServerHttpRequest(request);
    }

    private static ServletServerHttpResponse response() {
        return new ServletServerHttpResponse(new MockHttpServletResponse());
    }
}
