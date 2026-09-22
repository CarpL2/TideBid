package io.github.carpl2.tidebid.realtime.infrastructure.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.carpl2.tidebid.realtime.application.port.RealtimeConnectionLeaseStore;
import io.github.carpl2.tidebid.realtime.application.port.AuctionSnapshotClient;
import io.github.carpl2.tidebid.contracts.RealtimeAuctionStatus;
import io.github.carpl2.tidebid.contracts.RealtimeSnapshot;
import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimePropertiesTest;
import io.github.carpl2.tidebid.realtime.infrastructure.metrics.RealtimeMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RealtimeWebSocketHandlerTest {

    @Mock
    private WebSocketSession session;

    @Mock
    private RealtimeConnectionLeaseStore leaseStore;

    @Mock
    private AuctionSnapshotClient snapshotClient;

    private RealtimeWebSocketHandler handler;
    private Map<String, Object> attributes;
    private ArrayList<TextMessage> sent;

    @BeforeEach
    void setUp() throws Exception {
        attributes = new ConcurrentHashMap<>();
        sent = new ArrayList<>();
        when(session.getAttributes()).thenReturn(attributes);
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        handler = new RealtimeWebSocketHandler(
                new RealtimeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                RealtimePropertiesTest.validProperties(), leaseStore);
    }

    @Test
    void respondsToPingWithPongUsingTheSameRequestId() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage("""
                {"type":"PING","protocolVersion":1,"requestId":"ping-1",
                 "payload":{"clientTime":"2026-09-22T08:00:00Z"}}
                """));

        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).getPayload()).contains("\"type\":\"PONG\"")
                .contains("\"requestId\":\"ping-1\"")
                .contains("2026-09-22T08:00:00Z");
    }

    @Test
    void rejectsTheTwentyFirstDistinctSubscription() throws Exception {
        handler.afterConnectionEstablished(session);
        for (int i = 1; i <= 21; i++) {
            handler.handleMessage(session, new TextMessage("""
                    {"type":"SUBSCRIBE","protocolVersion":1,"requestId":"sub-%d",
                     "payload":{"auctionId":"%d","lastSequenceNo":0}}
                    """.formatted(i, i)));
        }

        assertThat(sent).anySatisfy(message -> assertThat(message.getPayload())
                .contains("SUBSCRIPTION_LIMIT_EXCEEDED"));
    }

    @Test
    void closesOversizedTextMessageWithSafeCloseCode() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage("x".repeat(9_000)));

        verify(session).close(new CloseStatus(1009, "message too large"));
    }

    @Test
    void subscribesThroughSnapshotBeforeLiveEvents() throws Exception {
        RealtimeWebSocketHandler snapshotHandler = new RealtimeWebSocketHandler(
                new RealtimeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                RealtimePropertiesTest.validProperties(), leaseStore,
                new io.github.carpl2.tidebid.realtime.infrastructure.fanout.RealtimeWebSocketSessionRegistry(
                        new ObjectMapper().findAndRegisterModules()), snapshotClient);
        attributes.put(RealtimeWebSocketAttributes.USER_ID, 42L);
        when(snapshotClient.find(7L, 3L, 100, 42L, "sub-1")).thenReturn(new RealtimeSnapshot(
                7L, RealtimeAuctionStatus.OPEN, new java.math.BigDecimal("10.00"),
                new java.math.BigDecimal("11.00"), 1L, Instant.parse("2026-09-22T09:00:00Z"),
                null, 0, 3L, true, false, java.util.List.of()));
        snapshotHandler.afterConnectionEstablished(session);
        snapshotHandler.handleMessage(session, new TextMessage("""
                {"type":"SUBSCRIBE","protocolVersion":1,"requestId":"sub-1",
                 "payload":{"auctionId":"7","lastSequenceNo":3}}
                """));

        verify(snapshotClient).find(7L, 3L, 100, 42L, "sub-1");
        assertThat(sent).anySatisfy(message -> assertThat(message.getPayload()).contains("\"type\":\"SNAPSHOT\""));
    }
}
