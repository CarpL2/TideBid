package io.github.carpl2.tidebid.trade.infrastructure.scheduling;

import io.github.carpl2.tidebid.trade.application.TradePaymentTimeoutService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile({"local-db", "nacos"})
@ConditionalOnProperty(
        prefix = "tidebid.trade.payment-timeout",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TradePaymentTimeoutJob {

    private final TradePaymentTimeoutService service;

    public TradePaymentTimeoutJob(TradePaymentTimeoutService service) {
        this.service = service;
    }

    @Scheduled(
            initialDelayString = "${tidebid.trade.payment-timeout.scan-interval:5s}",
            fixedDelayString = "${tidebid.trade.payment-timeout.scan-interval:5s}"
    )
    public TradePaymentTimeoutService.ScanResult expireDueOrders() {
        return service.scanDue();
    }
}
