package io.github.carpl2.tidebid.auction.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionOutboxPropertiesTest {

    @Test
    void rejectsUnsafePublisherLimits() {
        assertThatThrownBy(() -> properties(Duration.ofMillis(99), 50, Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMinutes(5), 16, Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scanInterval");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 0, Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMinutes(5), 16, Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batchSize");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 50, Duration.ofMillis(999),
                Duration.ofSeconds(1), Duration.ofMinutes(5), 16, Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("leaseDuration");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 50, Duration.ofSeconds(30),
                Duration.ofMinutes(6), Duration.ofMinutes(5), 16, Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be shorter");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 50, Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMinutes(5), 101, Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maximumAttempts");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 50, Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMinutes(5), 16, Duration.ofHours(49)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("delaySafeHorizon");
    }

    private static AuctionOutboxProperties properties(
            Duration scanInterval,
            int batchSize,
            Duration leaseDuration,
            Duration initialBackoff,
            Duration maximumBackoff,
            int maximumAttempts,
            Duration delaySafeHorizon
    ) {
        return new AuctionOutboxProperties(scanInterval, batchSize, leaseDuration, initialBackoff,
                maximumBackoff, maximumAttempts, delaySafeHorizon);
    }
}
