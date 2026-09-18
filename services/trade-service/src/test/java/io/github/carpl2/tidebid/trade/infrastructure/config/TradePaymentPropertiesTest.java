package io.github.carpl2.tidebid.trade.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradePaymentPropertiesTest {

    @Test
    void acceptsBoundedRecoveryDelay() {
        assertThat(new TradePaymentProperties(Duration.ofSeconds(5)).initialRecoveryDelay())
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void rejectsUnsafeRecoveryDelay() {
        assertThatThrownBy(() -> new TradePaymentProperties(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TradePaymentProperties(Duration.ofMinutes(6)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
