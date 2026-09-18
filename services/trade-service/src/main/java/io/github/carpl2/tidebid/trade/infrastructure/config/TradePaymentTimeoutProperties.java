package io.github.carpl2.tidebid.trade.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("tidebid.trade.payment-timeout")
public record TradePaymentTimeoutProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5s") Duration scanInterval,
        @DefaultValue("50") int batchSize
) {
    public TradePaymentTimeoutProperties {
        scanInterval = Objects.requireNonNull(scanInterval, "scanInterval must not be null");
        if (scanInterval.compareTo(Duration.ofSeconds(1)) < 0
                || scanInterval.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("scanInterval must be between 1 second and 5 minutes");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
    }
}
