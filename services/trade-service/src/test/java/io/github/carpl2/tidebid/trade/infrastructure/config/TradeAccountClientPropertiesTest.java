package io.github.carpl2.tidebid.trade.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeAccountClientPropertiesTest {

    @Test
    void redactsAndValidatesInternalToken() {
        String token = "x".repeat(32);
        var properties = new TradeAccountClientProperties("  " + token + "  ");
        assertThat(properties.requiredInternalToken()).isEqualTo(token);
        assertThat(properties.toString()).doesNotContain(token).contains("[REDACTED]");
        assertThatThrownBy(() -> new TradeAccountClientProperties("short").requiredInternalToken())
                .isInstanceOf(IllegalStateException.class);
    }
}
