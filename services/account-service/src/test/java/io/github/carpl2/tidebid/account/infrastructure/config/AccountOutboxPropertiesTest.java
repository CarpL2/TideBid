package io.github.carpl2.tidebid.account.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountOutboxPropertiesTest {

    @Test
    void rejectsInvalidBackoffAndBatchLimits() {
        assertThatThrownBy(() -> new AccountOutboxProperties(
                Duration.ofSeconds(1), 1001, Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofMinutes(5), 16, Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batchSize");
        assertThatThrownBy(() -> new AccountOutboxProperties(
                Duration.ofSeconds(1), 50, Duration.ofSeconds(30), Duration.ofMillis(99),
                Duration.ofMinutes(5), 16, Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("initialBackoff");
    }
}
