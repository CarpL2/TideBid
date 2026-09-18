package io.github.carpl2.tidebid.trade.infrastructure.scheduling;

import io.github.carpl2.tidebid.trade.application.TradePaymentRecoveryService;
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
        prefix = "tidebid.trade.payment",
        name = "recovery-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TradePaymentRecoveryJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(TradePaymentRecoveryJob.class);

    private final TradePaymentRecoveryService recoveryService;
    private final String leaseOwner = "trade-payment-" + UUID.randomUUID();

    public TradePaymentRecoveryJob(TradePaymentRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Scheduled(
            initialDelayString = "${tidebid.trade.payment.scan-interval:5s}",
            fixedDelayString = "${tidebid.trade.payment.scan-interval:5s}"
    )
    public void recoverUnknownPayments() {
        TradePaymentRecoveryService.RecoveryResult result = recoveryService.recoverBatch(leaseOwner);
        if (result.exhausted() > 0 || result.pending() > 0) {
            LOGGER.warn(
                    "Payment recovery completed claimed={} succeeded={} rejected={} pending={} exhausted={}",
                    result.claimed(), result.succeeded(), result.rejected(), result.pending(), result.exhausted()
            );
        } else if (result.claimed() > 0) {
            LOGGER.info(
                    "Payment recovery completed claimed={} succeeded={} rejected={} pending={} exhausted={}",
                    result.claimed(), result.succeeded(), result.rejected(), result.pending(), result.exhausted()
            );
        }
    }
}
