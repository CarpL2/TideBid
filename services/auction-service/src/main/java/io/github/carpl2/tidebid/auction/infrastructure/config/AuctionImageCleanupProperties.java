package io.github.carpl2.tidebid.auction.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("tidebid.auction.image-cleanup")
public record AuctionImageCleanupProperties(Duration scanInterval, int batchSize) {

    public AuctionImageCleanupProperties {
        scanInterval = Objects.requireNonNull(scanInterval, "scanInterval must not be null");
        if (scanInterval.compareTo(Duration.ofSeconds(10)) < 0
                || scanInterval.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("scanInterval must be between 10 seconds and 24 hours");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
    }
}
