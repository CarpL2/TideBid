package io.github.carpl2.tidebid.trade.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeRocketMqPropertiesTest {

    @Test
    void rejectsUnboundedTimeoutAndWrongDelayTopic() {
        var groups = new TradeRocketMqProperties.ConsumerGroups(
                "tidebid-trade-auction-v1", "tidebid-trade-account-v1", "tidebid-trade-timeout-v1");

        assertThatThrownBy(() -> new TradeRocketMqProperties(
                "127.0.0.1:8081",
                Duration.ofSeconds(31),
                2,
                new TradeRocketMqProperties.Topics(
                        "tidebid-trade-events", "tidebid-auction-events",
                        "tidebid-account-events", "tidebid-scheduled-commands"),
                groups
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestTimeout");
        assertThatThrownBy(() -> new TradeRocketMqProperties.Topics(
                "tidebid-trade-events", "tidebid-auction-events",
                "tidebid-account-events", "tidebid-trade-events"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bootstrapped");
    }
}
