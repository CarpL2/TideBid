package io.github.carpl2.tidebid.auction.infrastructure.scheduling;

import io.github.carpl2.tidebid.auction.application.AuctionSessionOpeningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile({"local-db", "nacos"})
@ConditionalOnProperty(
        prefix = "tidebid.auction.timing",
        name = "opening-scan-enabled",
        havingValue = "true"
)
public class AuctionSessionOpeningJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuctionSessionOpeningJob.class);

    private final AuctionSessionOpeningService openingService;

    public AuctionSessionOpeningJob(AuctionSessionOpeningService openingService) {
        this.openingService = openingService;
    }

    @Scheduled(
            initialDelayString = "${tidebid.auction.timing.opening-scan-interval}",
            fixedDelayString = "${tidebid.auction.timing.opening-scan-interval}"
    )
    public void openDueSessions() {
        AuctionSessionOpeningService.OpeningResult result = openingService.openDueSessions();
        if (result.candidates() > 0) {
            LOGGER.info(
                    "Auction session opening scan completed candidates={} opened={} unchanged={}",
                    result.candidates(), result.opened(), result.unchanged()
            );
        }
    }
}
