package io.github.carpl2.tidebid.realtime.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class RealtimePropertiesTest {

    @Test
    void acceptsTheDocumentedLimitsAndRedactsTheInternalToken() {
        RealtimeProperties properties = validProperties();

        assertThat(properties.ticket().ttl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.queue().maxClientMessageSize()).isEqualTo(DataSize.ofKilobytes(8));
        assertThat(properties.auctionClient().requiredInternalToken()).hasSize(32);
        assertThat(properties.auctionClient().toString()).doesNotContain("x".repeat(32));
    }

    @Test
    void rejectsUnsafeConnectionAndQueueLimits() {
        assertThatThrownBy(() -> new RealtimeProperties.Connection(0, Duration.ofSeconds(120)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeProperties.Queue(
                8, DataSize.ofKilobytes(8), Duration.ofSeconds(10), 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeProperties.Queue(
                128, DataSize.ofKilobytes(65), Duration.ofSeconds(10), 30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidHeartbeatEndpointAndTopology() {
        assertThatThrownBy(() -> new RealtimeProperties.Heartbeat(
                Duration.ofSeconds(30), Duration.ofSeconds(40)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeProperties.RocketMq(
                true, "http://localhost:8081", Duration.ofSeconds(3),
                "wrong-group", "tidebid-auction-events"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enabledAuctionClientRequiresASecretOnlyWhenUsed() {
        RealtimeProperties.AuctionClient client = new RealtimeProperties.AuctionClient(
                true, "", "short", Duration.ofSeconds(2), Duration.ofSeconds(3));
        assertThatThrownBy(client::requiredInternalToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TIDEBID_INTERNAL_SERVICE_TOKEN");
    }

    public static RealtimeProperties validProperties() {
        return new RealtimeProperties(
                new RealtimeProperties.Ticket(Duration.ofSeconds(30), Duration.ofSeconds(60), 10),
                new RealtimeProperties.Connection(5, Duration.ofSeconds(120)),
                new RealtimeProperties.Subscription(20, 128),
                new RealtimeProperties.Heartbeat(Duration.ofSeconds(30), Duration.ofSeconds(90)),
                new RealtimeProperties.Queue(128, DataSize.ofKilobytes(8), Duration.ofSeconds(10), 30),
                new RealtimeProperties.AuctionClient(
                        true, "", "x".repeat(32), Duration.ofSeconds(2), Duration.ofSeconds(3)),
                new RealtimeProperties.Redis(true),
                new RealtimeProperties.RocketMq(
                        true, "127.0.0.1:8081", Duration.ofSeconds(3),
                        "tidebid-realtime-auction-v1", "tidebid-auction-events")
        );
    }
}
