package io.github.carpl2.tidebid.realtime.infrastructure.fanout;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.BidAcceptedEvent;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.realtime.infrastructure.websocket.RealtimeWebSocketAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RealtimeWebSocketSessionRegistryTest {

    @Test
    void broadcastsBidWithMineFromBoundSessionIdentity() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RealtimeWebSocketAttributes.USER_ID, 7L);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        RealtimeWebSocketSessionRegistry registry = new RealtimeWebSocketSessionRegistry(
                new ObjectMapper().findAndRegisterModules());
        registry.subscribe(42L, session);

        EventEnvelope<BidAcceptedEvent> envelope = new EventEnvelope<>(
                java.util.UUID.randomUUID(), BidAcceptedEvent.EVENT_TYPE, 1,
                Instant.parse("2026-09-22T08:00:00Z"), "auction",
                new BidAcceptedEvent(42L, 10L, 7L, new BigDecimal("12.00"), 1L,
                        Instant.parse("2026-09-22T08:00:00Z")));
        registry.broadcast(envelope);

        org.mockito.ArgumentCaptor<TextMessage> captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        TextMessage message = captor.getValue();
        assertThat(message.getPayload()).contains("\"mine\":true").doesNotContain("bidderId");
    }
}
