package io.github.carpl2.tidebid.account.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("tidebid.messaging.outbox")
public record AccountOutboxProperties(
        @DefaultValue("1s") Duration scanInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("30s") Duration leaseDuration,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("5m") Duration maximumBackoff,
        @DefaultValue("16") int maximumAttempts,
        @DefaultValue("48h") Duration delaySafeHorizon
) {

    public AccountOutboxProperties {
        scanInterval = requireBetween(
                scanInterval, Duration.ofMillis(100), Duration.ofMinutes(1), "scanInterval");
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        leaseDuration = requireBetween(
                leaseDuration, Duration.ofSeconds(1), Duration.ofMinutes(10), "leaseDuration");
        initialBackoff = requireBetween(
                initialBackoff, Duration.ofMillis(100), Duration.ofHours(1), "initialBackoff");
        maximumBackoff = requireBetween(
                maximumBackoff, Duration.ofSeconds(1), Duration.ofHours(24), "maximumBackoff");
        if (maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maximumBackoff must not be shorter than initialBackoff");
        }
        if (maximumAttempts < 1 || maximumAttempts > 100) {
            throw new IllegalArgumentException("maximumAttempts must be between 1 and 100");
        }
        delaySafeHorizon = requireBetween(
                delaySafeHorizon, Duration.ofMinutes(1), Duration.ofHours(48), "delaySafeHorizon");
    }

    private static Duration requireBetween(Duration value, Duration minimum, Duration maximum, String name) {
        value = Objects.requireNonNull(value, name + " must not be null");
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " is outside the allowed range");
        }
        return value;
    }
}
