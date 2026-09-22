package io.github.carpl2.tidebid.realtime.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;
import java.util.Objects;

@ConfigurationProperties("tidebid.realtime.websocket")
public record RealtimeWebSocketProperties(boolean enabled, List<String> allowedOrigins) {

    public RealtimeWebSocketProperties {
        allowedOrigins = List.copyOf(Objects.requireNonNull(allowedOrigins, "allowedOrigins must not be null"))
                .stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toList();
        for (String origin : allowedOrigins) {
            URI parsed;
            try {
                parsed = URI.create(origin);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("websocket.allowed-origins must contain valid origins", exception);
            }
            if (!(("http".equalsIgnoreCase(parsed.getScheme())
                    || "https".equalsIgnoreCase(parsed.getScheme()))
                    && parsed.getHost() != null
                    && parsed.getUserInfo() == null
                    && (parsed.getPath() == null || parsed.getPath().isEmpty())
                    && parsed.getQuery() == null
                    && parsed.getFragment() == null)) {
                throw new IllegalArgumentException("websocket.allowed-origins must contain absolute HTTP(S) origins");
            }
        }
        if (enabled && allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("websocket.allowed-origins must not be empty when websocket is enabled");
        }
    }

    public boolean allows(String origin) {
        return origin != null && allowedOrigins.contains(origin.trim());
    }
}
