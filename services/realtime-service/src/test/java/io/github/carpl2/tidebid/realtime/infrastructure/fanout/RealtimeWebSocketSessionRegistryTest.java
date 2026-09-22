package io.github.carpl2.tidebid.realtime.infrastructure.fanout;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.BidAcceptedEvent;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.RealtimeAuctionStatus;
import io.github.carpl2.tidebid.contracts.RealtimeSnapshot;
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

    @Test
    void sendsSnapshotBeforeBufferedEventsAndDropsOverlappingSequences() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RealtimeWebSocketAttributes.USER_ID, 7L);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        RealtimeWebSocketSessionRegistry registry = new RealtimeWebSocketSessionRegistry(
                new ObjectMapper().findAndRegisterModules());
        registry.subscribe(42L, session);
        registry.beginSync(42L, session, 8);

        EventEnvelope<BidAcceptedEvent> first = bidEvent(10L, 1L, 7L, "2026-09-22T08:00:00Z");
        EventEnvelope<BidAcceptedEvent> second = bidEvent(11L, 2L, 8L, "2026-09-22T08:00:01Z");
        registry.broadcast(first);
        registry.broadcast(second);

        RealtimeSnapshot snapshot = new RealtimeSnapshot(
                42L, RealtimeAuctionStatus.OPEN, new BigDecimal("12.00"),
                new BigDecimal("13.00"), 1L, Instant.parse("2026-09-22T09:00:00Z"),
                null, 0, 1L, true, false,
                java.util.List.of(new io.github.carpl2.tidebid.contracts.RealtimeBidView(
                        10L, new BigDecimal("12.00"), 1L, true,
                        Instant.parse("2026-09-22T08:00:00Z"))));

        assertThat(registry.sendSnapshot(42L, session, snapshot, "sub-1")).isTrue();
        org.mockito.ArgumentCaptor<TextMessage> captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(2)).sendMessage(captor.capture());
        assertThat(captor.getAllValues().get(0).getPayload()).contains("\"type\":\"SNAPSHOT\"");
        assertThat(captor.getAllValues().get(1).getPayload()).contains("\"bidId\":\"11\"")
                .doesNotContain("\"bidId\":\"10\"");
    }

    private static EventEnvelope<BidAcceptedEvent> bidEvent(long bidId, long sequenceNo, long bidderId,
                                                              String acceptedAt) {
        Instant timestamp = Instant.parse(acceptedAt);
        return new EventEnvelope<>(java.util.UUID.randomUUID(), BidAcceptedEvent.EVENT_TYPE, 1,
                timestamp, "auction",
                new BidAcceptedEvent(42L, bidId, bidderId, new BigDecimal("12.00"), sequenceNo, timestamp));
    }
}
