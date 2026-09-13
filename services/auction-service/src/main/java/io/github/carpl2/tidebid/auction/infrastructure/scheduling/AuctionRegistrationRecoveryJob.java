package io.github.carpl2.tidebid.auction.infrastructure.scheduling;

import io.github.carpl2.tidebid.auction.application.AuctionRegistrationRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile({"local-db", "nacos"})
@ConditionalOnProperty(
        prefix = "tidebid.auction.registration-recovery",
        name = "enabled",
        havingValue = "true"
)
public class AuctionRegistrationRecoveryJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuctionRegistrationRecoveryJob.class);

    private final AuctionRegistrationRecoveryService recoveryService;
    private final String leaseOwner = "auction-recovery-" + UUID.randomUUID();

    public AuctionRegistrationRecoveryJob(AuctionRegistrationRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Scheduled(
            initialDelayString = "${tidebid.auction.registration-recovery.scan-interval}",
            fixedDelayString = "${tidebid.auction.registration-recovery.scan-interval}"
    )
    public void recoverPendingRegistrations() {
        AuctionRegistrationRecoveryService.RecoveryResult result = recoveryService.recoverBatch(leaseOwner);
        if (result.failed() > 0 || result.pending() > 0) {
            LOGGER.warn(
                    "Registration recovery completed claimed={} registered={} failed={} pending={}",
                    result.claimed(), result.registered(), result.failed(), result.pending()
            );
        } else if (result.claimed() > 0) {
            LOGGER.info(
                    "Registration recovery completed claimed={} registered={} failed={} pending={}",
                    result.claimed(), result.registered(), result.failed(), result.pending()
            );
        }
    }
}
