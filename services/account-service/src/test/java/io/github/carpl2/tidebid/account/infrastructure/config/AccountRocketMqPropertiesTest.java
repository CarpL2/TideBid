package io.github.carpl2.tidebid.account.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountRocketMqPropertiesTest {

    @Test
    void rejectsInvalidEndpointAndForeignConsumerGroup() {
        var topics = new AccountRocketMqProperties.Topics(
                "tidebid-account-events", "tidebid-auction-events", "tidebid-trade-events");
        var groups = new AccountRocketMqProperties.ConsumerGroups(
                "tidebid-account-deposit-v1", "tidebid-account-credit-v1");

        assertThatThrownBy(() -> new AccountRocketMqProperties(
                "127.0.0.1:70000", Duration.ofSeconds(3), 2, topics, groups))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("port");
        assertThatThrownBy(() -> new AccountRocketMqProperties.ConsumerGroups(
                "tidebid-account-deposit-v1", "tidebid-trade-account-v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bootstrapped");
    }
}
