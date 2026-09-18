package io.github.carpl2.tidebid.trade.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeOrderPropertiesTest {

    @Test
    void acceptsPaymentWindowBoundaries() {
        assertThat(new TradeOrderProperties(Duration.ofMinutes(1)).paymentWindow())
                .isEqualTo(Duration.ofMinutes(1));
        assertThat(new TradeOrderProperties(Duration.ofHours(24)).paymentWindow())
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    void rejectsPaymentWindowOutsideSafeRange() {
        assertThatThrownBy(() -> new TradeOrderProperties(Duration.ofSeconds(59)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paymentWindow");
        assertThatThrownBy(() -> new TradeOrderProperties(Duration.ofHours(24).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paymentWindow");
    }
}
