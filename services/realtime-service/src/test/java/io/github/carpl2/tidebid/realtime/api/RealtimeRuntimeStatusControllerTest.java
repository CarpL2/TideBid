package io.github.carpl2.tidebid.realtime.api;

import io.github.carpl2.tidebid.realtime.infrastructure.config.RealtimeProperties;
import io.github.carpl2.tidebid.realtime.infrastructure.fanout.RealtimeWebSocketSessionRegistry;
import io.github.carpl2.tidebid.realtime.infrastructure.messaging.RealtimeRocketMqTransport;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;

class RealtimeRuntimeStatusControllerTest {

    private static final String TOKEN = "runtime-observation-token-123456789012";

    @Test
    void rejectsMissingOrForgedToken() {
        RealtimeRuntimeStatusController controller = controller(true, true);

        assertThat(controller.status(null).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.status("forged").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void returnsOnlyAggregatedRuntimeState() {
        RealtimeRuntimeStatusController controller = controller(true, false);

        ResponseEntity<RealtimeRuntimeStatusController.RuntimeStatus> response =
                controller.status(TOKEN);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("DEGRADED");
        assertThat(response.getBody().connections()).isZero();
        assertThat(response.getBody().subscriptions()).isZero();
        assertThat(response.getBody().syncingSubscriptions()).isZero();
        assertThat(response.getBody().redisListenerRunning()).isTrue();
        assertThat(response.getBody().rocketMqConsumerRunning()).isFalse();
    }

    private static RealtimeRuntimeStatusController controller(boolean redisRunning, boolean rocketMqRunning) {
        RealtimeProperties properties = new RealtimeProperties(
                new RealtimeProperties.Ticket(Duration.ofSeconds(30), Duration.ofSeconds(60), 10),
                new RealtimeProperties.Connection(5, Duration.ofSeconds(120)),
                new RealtimeProperties.Subscription(20, 128),
                new RealtimeProperties.Heartbeat(Duration.ofSeconds(30), Duration.ofSeconds(90)),
                new RealtimeProperties.Queue(128, DataSize.ofKilobytes(8), Duration.ofSeconds(10), 30),
                new RealtimeProperties.AuctionClient(true, "", TOKEN, Duration.ofSeconds(2), Duration.ofSeconds(3)),
                new RealtimeProperties.Redis(true, Duration.ofHours(2)),
                new RealtimeProperties.RocketMq(true, "127.0.0.1:8081", Duration.ofSeconds(3),
                        "tidebid-realtime-auction-v1", "tidebid-auction-events"));
        RealtimeWebSocketSessionRegistry registry = new RealtimeWebSocketSessionRegistry(
                new com.fasterxml.jackson.databind.ObjectMapper(), 128, Runnable::run);
        RedisMessageListenerContainer redis = mock(RedisMessageListenerContainer.class);
        when(redis.isRunning()).thenReturn(redisRunning);
        RealtimeRocketMqTransport rocketMq = mock(RealtimeRocketMqTransport.class);
        when(rocketMq.isRunning()).thenReturn(rocketMqRunning);
        return new RealtimeRuntimeStatusController(properties, registry, redis, rocketMq);
    }
}
