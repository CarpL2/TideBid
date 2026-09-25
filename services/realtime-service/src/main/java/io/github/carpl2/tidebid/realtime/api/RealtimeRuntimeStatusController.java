package io.github.carpl2.tidebid.realtime.api;

import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeProperties;
import io.github.carpl2.tidebid.realtime.infrastructure.fanout.RealtimeWebSocketSessionRegistry;
import io.github.carpl2.tidebid.realtime.infrastructure.messaging.RealtimeRocketMqTransport;
import io.github.carpl2.tidebid.security.SecurityHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Local service-observation endpoint. It is intentionally not routed through Gateway
 * and never returns identities, connection IDs, auction IDs, or credentials.
 */
@RestController
@Profile({"local-db", "nacos"})
@ConditionalOnProperty(prefix = "tidebid.realtime.redis", name = "enabled", havingValue = "true")
@RequestMapping("/internal/realtime")
public final class RealtimeRuntimeStatusController {

    private final String expectedToken;
    private final RealtimeWebSocketSessionRegistry sessions;
    private final RedisMessageListenerContainer redisListener;
    private final RealtimeRocketMqTransport rocketMq;

    public RealtimeRuntimeStatusController(
            RealtimeProperties properties,
            RealtimeWebSocketSessionRegistry sessions,
            RedisMessageListenerContainer redisListener,
            RealtimeRocketMqTransport rocketMq
    ) {
        this.expectedToken = properties.auctionClient().requiredInternalToken();
        this.sessions = sessions;
        this.redisListener = redisListener;
        this.rocketMq = rocketMq;
    }

    @GetMapping("/status")
    public ResponseEntity<RuntimeStatus> status(
            @RequestHeader(value = SecurityHeaders.INTERNAL_SERVICE_TOKEN, required = false) String token
    ) {
        if (!matches(token)) return ResponseEntity.status(401).build();
        boolean redisRunning = redisListener.isRunning();
        boolean rocketMqRunning = rocketMq.isRunning();
        String state = redisRunning && rocketMqRunning ? "UP" : "DEGRADED";
        return ResponseEntity.ok(new RuntimeStatus(
                state,
                sessions.connectionCount(),
                sessions.subscriptionCount(),
                sessions.syncingSubscriptionCount(),
                redisRunning,
                rocketMqRunning));
    }

    private boolean matches(String provided) {
        if (provided == null) return false;
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    public record RuntimeStatus(
            String status,
            int connections,
            int subscriptions,
            int syncingSubscriptions,
            boolean redisListenerRunning,
            boolean rocketMqConsumerRunning
    ) {
    }
}
