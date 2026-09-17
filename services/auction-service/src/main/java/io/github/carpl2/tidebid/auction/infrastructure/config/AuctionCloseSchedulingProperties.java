package io.github.carpl2.tidebid.auction.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("tidebid.auction.close-scheduling")
public record AuctionCloseSchedulingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10s") java.time.Duration scanInterval,
        @DefaultValue("100") int batchSize
) {
    public AuctionCloseSchedulingProperties {
        if (scanInterval == null
                || scanInterval.compareTo(java.time.Duration.ofSeconds(1)) < 0
                || scanInterval.compareTo(java.time.Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("scanInterval must be between 1 second and 5 minutes");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
    }
}
