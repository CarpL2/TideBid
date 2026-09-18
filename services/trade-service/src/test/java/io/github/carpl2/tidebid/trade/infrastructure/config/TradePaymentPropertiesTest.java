package io.github.carpl2.tidebid.trade.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradePaymentPropertiesTest {

    @Test
    void acceptsBoundedRecoveryDelay() {
        assertThat(properties(Duration.ofSeconds(5)).initialRecoveryDelay())
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void rejectsUnsafeRecoveryDelay() {
        assertThatThrownBy(() -> properties(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofMinutes(6)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TradePaymentProperties properties(Duration initialRecoveryDelay) {
        return new TradePaymentProperties(true, initialRecoveryDelay, Duration.ofSeconds(5),
                Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(5), 20, 12);
    }
}
