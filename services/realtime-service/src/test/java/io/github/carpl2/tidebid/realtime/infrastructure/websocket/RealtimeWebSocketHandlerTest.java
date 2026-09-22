package io.github.carpl2.tidebid.realtime.infrastructure.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.carpl2.tidebid.realtime.application.port.RealtimeConnectionLeaseStore;
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
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RealtimeWebSocketHandlerTest {

    @Mock
    private WebSocketSession session;

    @Mock
    private RealtimeConnectionLeaseStore leaseStore;

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
}
