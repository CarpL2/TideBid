package io.github.carpl2.tidebid.realtime.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealtimeWebSocketPropertiesTest {

    @Test
    void acceptsOnlyConfiguredHttpOrigins() {
        RealtimeWebSocketProperties properties = new RealtimeWebSocketProperties(
                true, List.of(" http://localhost:5173 ", "http://localhost:5173", "https://app.example.com"));

        assertThat(properties.allowedOrigins()).containsExactly("http://localhost:5173", "https://app.example.com");
        assertThat(properties.allows("http://localhost:5173")).isTrue();
        assertThat(properties.allows("http://evil.example")).isFalse();
    }

    @Test
    void enabledWebSocketRequiresAnOriginAllowList() {
        assertThatThrownBy(() -> new RealtimeWebSocketProperties(true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeWebSocketProperties(true, List.of("/relative")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeWebSocketProperties(true, List.of("http://localhost:5173/app")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
