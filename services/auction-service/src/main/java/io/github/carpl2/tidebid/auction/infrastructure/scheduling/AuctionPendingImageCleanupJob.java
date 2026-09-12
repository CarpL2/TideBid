package io.github.carpl2.tidebid.auction.infrastructure.scheduling;

import io.github.carpl2.tidebid.auction.application.AuctionPendingImageCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile({"local-db", "nacos"})
@ConditionalOnProperty(prefix = "tidebid.auction.storage", name = "enabled", havingValue = "true")
public class AuctionPendingImageCleanupJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuctionPendingImageCleanupJob.class);

    private final AuctionPendingImageCleanupService cleanupService;

    public AuctionPendingImageCleanupJob(AuctionPendingImageCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Scheduled(
            initialDelayString = "${tidebid.auction.image-cleanup.scan-interval}",
            fixedDelayString = "${tidebid.auction.image-cleanup.scan-interval}"
    )
    public void cleanupExpiredPendingImages() {
        AuctionPendingImageCleanupService.CleanupResult result = cleanupService.cleanupBatch();
        if (result.failed() > 0) {
            LOGGER.warn(
                    "Pending image cleanup completed with failures candidates={} deleted={} expired={} skipped={} failed={}",
                    result.candidates(), result.deleted(), result.expired(), result.skipped(), result.failed()
            );
        } else if (result.candidates() > 0) {
            LOGGER.info(
                    "Pending image cleanup completed candidates={} deleted={} expired={} skipped={} failed={}",
                    result.candidates(), result.deleted(), result.expired(), result.skipped(), result.failed()
            );
        }
    }
}
