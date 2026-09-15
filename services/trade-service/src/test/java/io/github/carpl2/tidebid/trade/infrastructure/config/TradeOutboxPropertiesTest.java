package io.github.carpl2.tidebid.trade.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeOutboxPropertiesTest {

    @Test
    void rejectsUnsafeLeaseAttemptAndDelayLimits() {
        assertThatThrownBy(() -> new TradeOutboxProperties(
                Duration.ofSeconds(1), 50, Duration.ofMinutes(11), Duration.ofSeconds(1),
                Duration.ofMinutes(5), 16, Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("leaseDuration");
        assertThatThrownBy(() -> new TradeOutboxProperties(
                Duration.ofSeconds(1), 50, Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofMinutes(5), 0, Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maximumAttempts");
        assertThatThrownBy(() -> new TradeOutboxProperties(
                Duration.ofSeconds(1), 50, Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofMinutes(5), 16, Duration.ofSeconds(59)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("delaySafeHorizon");
    }
}
