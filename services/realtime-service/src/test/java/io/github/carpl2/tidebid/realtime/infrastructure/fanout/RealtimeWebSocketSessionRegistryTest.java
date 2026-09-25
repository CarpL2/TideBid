package io.github.carpl2.tidebid.realtime.infrastructure.fanout;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.BidAcceptedEvent;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.RealtimeAuctionStatus;
import io.github.carpl2.tidebid.contracts.RealtimeConnected;
import io.github.carpl2.tidebid.contracts.RealtimeMessageType;
import io.github.carpl2.tidebid.contracts.RealtimeResyncReason;
import io.github.carpl2.tidebid.contracts.RealtimeResyncRequired;
import io.github.carpl2.tidebid.contracts.RealtimeServerMessage;
import io.github.carpl2.tidebid.contracts.RealtimeSnapshot;
import io.github.carpl2.tidebid.realtime.infrastructure.websocket.RealtimeWebSocketAttributes;
import io.github.carpl2.tidebid.realtime.infrastructure.websocket.RealtimeWebSocketSendQueue;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

    @Test
    void dropsDuplicateEventIdsWhileReplayingSyncBuffer() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RealtimeWebSocketAttributes.USER_ID, 7L);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        RealtimeWebSocketSessionRegistry registry = new RealtimeWebSocketSessionRegistry(
                new ObjectMapper().findAndRegisterModules());
        registry.subscribe(42L, session);
        registry.beginSync(42L, session, 8);

        EventEnvelope<BidAcceptedEvent> duplicate = bidEventWithId(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), 10L, 1L, 8L,
                "2026-09-22T08:00:00Z");
        registry.broadcast(duplicate);
        registry.broadcast(duplicate);

        RealtimeSnapshot snapshot = new RealtimeSnapshot(
                42L, RealtimeAuctionStatus.OPEN, new BigDecimal("12.00"),
                new BigDecimal("13.00"), 0L, Instant.parse("2026-09-22T09:00:00Z"),
                null, 0, 0L, false, false, List.of());

        assertThat(registry.sendSnapshot(42L, session, snapshot, "sub-duplicate")).isTrue();
        org.mockito.ArgumentCaptor<TextMessage> captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(2)).sendMessage(captor.capture());
        assertThat(captor.getAllValues().get(0).getPayload()).contains("\"type\":\"SNAPSHOT\"");
        assertThat(captor.getAllValues().get(1).getPayload()).contains("\"bidId\":\"10\"");
    }

    @Test
    void broadcastWithoutSubscribersDoesNotCreateSessionState() {
        RealtimeWebSocketSessionRegistry registry = new RealtimeWebSocketSessionRegistry(
                new ObjectMapper().findAndRegisterModules());

        registry.broadcast(bidEvent(12L, 1L, 7L, "2026-09-22T08:00:00Z"));

        assertThat(registry.sessions()).isEmpty();
    }

    @Test
    void slowConsumerOverflowDoesNotCloseHealthyConsumer() throws Exception {
        WebSocketSession slow = mock(WebSocketSession.class);
        WebSocketSession healthy = mock(WebSocketSession.class);
        when(slow.isOpen()).thenReturn(true);
        when(healthy.isOpen()).thenReturn(true);

        List<Runnable> drains = new ArrayList<>();
        RealtimeServerMessage<RealtimeConnected> connected = new RealtimeServerMessage<>(
                RealtimeMessageType.CONNECTED, 1, "connected-1", Instant.parse("2026-09-24T00:00:00Z"),
                new RealtimeConnected("connection-1", Instant.parse("2026-09-24T00:00:00Z"), 15));
        RealtimeWebSocketSendQueue slowQueue = new RealtimeWebSocketSendQueue(
                slow, new ObjectMapper().findAndRegisterModules(), 1, drains::add);
        RealtimeWebSocketSendQueue healthyQueue = new RealtimeWebSocketSendQueue(
                healthy, new ObjectMapper().findAndRegisterModules(), 1, drains::add);

        assertThat(slowQueue.offer(connected)).isTrue();
        assertThat(healthyQueue.offer(connected)).isTrue();
        drains.get(1).run();
        assertThat(healthyQueue.offer(connected)).isTrue();
        assertThat(slowQueue.offer(connected)).isFalse();

        RealtimeServerMessage<RealtimeResyncRequired> recovery = new RealtimeServerMessage<>(
                RealtimeMessageType.RESYNC_REQUIRED, 1, "recovery-1", Instant.parse("2026-09-24T00:00:01Z"),
                new RealtimeResyncRequired(42L, RealtimeResyncReason.BUFFER_OVERFLOW, 7L));
        slowQueue.overflow(recovery);
        drains.get(0).run();

        verify(slow).sendMessage(any(TextMessage.class));
        org.mockito.ArgumentCaptor<CloseStatus> slowClose = org.mockito.ArgumentCaptor.forClass(CloseStatus.class);
        verify(slow).close(slowClose.capture());
        assertThat(slowClose.getValue().getCode()).isEqualTo(1013);
        verify(healthy).sendMessage(any(TextMessage.class));
        verify(healthy, never()).close(any(CloseStatus.class));
    }

    private static EventEnvelope<BidAcceptedEvent> bidEvent(long bidId, long sequenceNo, long bidderId,
                                                              String acceptedAt) {
        return bidEventWithId(UUID.randomUUID(), bidId, sequenceNo, bidderId, acceptedAt);
    }

    private static EventEnvelope<BidAcceptedEvent> bidEventWithId(UUID eventId, long bidId, long sequenceNo,
                                                                   long bidderId, String acceptedAt) {
        Instant timestamp = Instant.parse(acceptedAt);
        return new EventEnvelope<>(eventId, BidAcceptedEvent.EVENT_TYPE, 1,
                timestamp, "auction",
                new BidAcceptedEvent(42L, bidId, bidderId, new BigDecimal("12.00"), sequenceNo, timestamp));
    }
}
