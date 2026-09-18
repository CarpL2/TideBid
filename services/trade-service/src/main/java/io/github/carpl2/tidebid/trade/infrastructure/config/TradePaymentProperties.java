package io.github.carpl2.tidebid.trade.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("tidebid.trade.payment")
public record TradePaymentProperties(@DefaultValue("5s") Duration initialRecoveryDelay) {

    public TradePaymentProperties {
        initialRecoveryDelay = Objects.requireNonNull(initialRecoveryDelay, "initialRecoveryDelay must not be null");
        if (initialRecoveryDelay.compareTo(Duration.ofSeconds(1)) < 0
                || initialRecoveryDelay.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("initialRecoveryDelay must be between 1 second and 5 minutes");
        }
    }
}
