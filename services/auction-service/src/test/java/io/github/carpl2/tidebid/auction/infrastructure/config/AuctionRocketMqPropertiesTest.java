package io.github.carpl2.tidebid.auction.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionRocketMqPropertiesTest {

    @Test
    void rejectsUnsafeClientLimitsAndTopologyDrift() {
        var topics = new AuctionRocketMqProperties.Topics(
                "tidebid-auction-events", "tidebid-scheduled-commands");
        var groups = new AuctionRocketMqProperties.ConsumerGroups("tidebid-auction-close-v1");

        assertThatThrownBy(() -> new AuctionRocketMqProperties(
                "http://127.0.0.1:8081", Duration.ofSeconds(3), 2, topics, groups))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host:port");
        assertThatThrownBy(() -> new AuctionRocketMqProperties(
                "127.0.0.1:8081", Duration.ofMillis(99), 2, topics, groups))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestTimeout");
        assertThatThrownBy(() -> new AuctionRocketMqProperties(
                "127.0.0.1:8081", Duration.ofSeconds(3), 6, topics, groups))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("producerRetryAttempts");
        assertThatThrownBy(() -> new AuctionRocketMqProperties.Topics(
                "tidebid-auction-events-typo", "tidebid-scheduled-commands"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bootstrapped");
    }
}
