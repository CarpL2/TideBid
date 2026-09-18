package io.github.carpl2.tidebid.trade.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradePaymentTimeoutPropertiesTest {

    @Test
    void acceptsSafeScannerConfiguration() {
        var properties = new TradePaymentTimeoutProperties(true, Duration.ofSeconds(5), 50);
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.scanInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.batchSize()).isEqualTo(50);
    }

    @Test
    void rejectsUnsafeScannerConfiguration() {
        assertThatThrownBy(() -> new TradePaymentTimeoutProperties(
                true, Duration.ZERO, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TradePaymentTimeoutProperties(
                true, Duration.ofSeconds(5), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
