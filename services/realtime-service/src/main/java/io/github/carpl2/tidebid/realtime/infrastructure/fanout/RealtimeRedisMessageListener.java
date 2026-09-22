package io.github.carpl2.tidebid.realtime.infrastructure.fanout;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.carpl2.tidebid.contracts.EventEnvelope;
import io.github.carpl2.tidebid.contracts.EventMessageDecoder;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.nio.charset.StandardCharsets;

public final class RealtimeRedisMessageListener implements MessageListener {

    private final EventMessageDecoder decoder;
    private final RealtimeWebSocketSessionRegistry registry;

    public RealtimeRedisMessageListener(ObjectMapper objectMapper, RealtimeWebSocketSessionRegistry registry) {
        this.decoder = new EventMessageDecoder(objectMapper);
        this.registry = registry;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        EventEnvelope<?> envelope = decoder.decode(message.getBody());
        registry.broadcast(envelope);
    }
}
