package io.github.carpl2.tidebid.realtime.infrastructure.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.realtime.infrastructure.metrics.RealtimeMetrics;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final RealtimeMetrics metrics;

    RealtimeWebSocketHandler(RealtimeMetrics metrics, ObjectMapper objectMapper) {
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        metrics.connectionOpened();
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "CONNECTED",
                "protocolVersion", 1,
                "connectionId", UUID.randomUUID().toString(),
                "serverTime", Instant.now().toString(),
                "heartbeatSeconds", 30
        ))));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        metrics.connectionClosed();
    }
}
