package io.github.carpl2.tidebid.auction.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("tidebid.auction.timing")
public record AuctionTimingProperties(
        Duration minimumLeadTime,
        Duration maximumDuration,
        Duration openingScanInterval
) {

    public AuctionTimingProperties {
        minimumLeadTime = requireBetween(
                minimumLeadTime, Duration.ofSeconds(1), Duration.ofDays(1), "minimumLeadTime");
        maximumDuration = requireBetween(
                maximumDuration, Duration.ofMinutes(1), Duration.ofDays(30), "maximumDuration");
        openingScanInterval = requireBetween(
                openingScanInterval, Duration.ofMillis(100), Duration.ofMinutes(1), "openingScanInterval");
        if (maximumDuration.compareTo(minimumLeadTime) <= 0) {
            throw new IllegalArgumentException("maximumDuration must be greater than minimumLeadTime");
        }
    }

    private static Duration requireBetween(Duration value, Duration minimum, Duration maximum, String name) {
        value = Objects.requireNonNull(value, name + " must not be null");
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " is outside the allowed range");
        }
        return value;
    }
}
