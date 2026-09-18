package io.github.carpl2.tidebid.trade.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("tidebid.trade.order")
public record TradeOrderProperties(@DefaultValue("30m") Duration paymentWindow) {

    public TradeOrderProperties {
        paymentWindow = Objects.requireNonNull(paymentWindow, "paymentWindow must not be null");
        if (paymentWindow.compareTo(Duration.ofMinutes(1)) < 0
                || paymentWindow.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("paymentWindow must be between 1 minute and 24 hours");
        }
    }
}
