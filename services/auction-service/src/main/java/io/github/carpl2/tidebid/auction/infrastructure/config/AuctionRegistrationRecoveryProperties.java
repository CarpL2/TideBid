package io.github.carpl2.tidebid.auction.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("tidebid.auction.registration-recovery")
public record AuctionRegistrationRecoveryProperties(
        Duration initialRetryDelay,
        Duration maximumRetryDelay,
        Duration leaseDuration,
        Duration scanInterval,
        int batchSize
) {

    public AuctionRegistrationRecoveryProperties {
        initialRetryDelay = requireBetween(
                initialRetryDelay, Duration.ofMillis(100), Duration.ofHours(1), "initialRetryDelay");
        maximumRetryDelay = requireBetween(
                maximumRetryDelay, Duration.ofSeconds(1), Duration.ofHours(24), "maximumRetryDelay");
        leaseDuration = requireBetween(
                leaseDuration, Duration.ofSeconds(1), Duration.ofMinutes(30), "leaseDuration");
        scanInterval = requireBetween(
                scanInterval, Duration.ofMillis(100), Duration.ofMinutes(30), "scanInterval");
        if (maximumRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw new IllegalArgumentException("maximumRetryDelay must not be shorter than initialRetryDelay");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
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
