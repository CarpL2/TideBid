package io.github.carpl2.tidebid.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("tidebid.gateway.rate-limit")
public record GatewayRateLimitProperties(
        Duration window,
        int registrationMaxRequests,
        int loginMaxRequests
) {

    private static final Duration MAXIMUM_WINDOW = Duration.ofDays(1);

    public GatewayRateLimitProperties {
        window = Objects.requireNonNull(window, "rate-limit window must not be null");
        if (window.isNegative() || window.toMillis() < 1L || window.compareTo(MAXIMUM_WINDOW) > 0) {
            throw new IllegalArgumentException("rate-limit window must be between 1 millisecond and 1 day");
        }
        requirePositive(registrationMaxRequests, "registrationMaxRequests");
        requirePositive(loginMaxRequests, "loginMaxRequests");
    }

    int maxRequests(GatewayRateLimitTarget target) {
        return switch (target) {
            case REGISTRATION -> registrationMaxRequests;
            case LOGIN -> loginMaxRequests;
        };
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
