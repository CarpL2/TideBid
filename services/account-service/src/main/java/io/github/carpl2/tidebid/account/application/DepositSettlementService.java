package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.application.port.DepositSettlementTransaction;
import io.github.carpl2.tidebid.contracts.DepositSettlementRequestedEvent;
import io.github.carpl2.tidebid.contracts.WalletHoldSettledEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
@Profile({"local-db", "nacos"})
public class DepositSettlementService {

    private final DepositSettlementTransaction transaction;

    public DepositSettlementService(DepositSettlementTransaction transaction) {
        this.transaction = transaction;
    }

    public WalletHoldSettledEvent settle(
            UUID sourceEventId,
            String traceId,
            DepositSettlementRequestedEvent request
    ) {
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        return transaction.settle(sourceEventId, traceId, request);
    }
}
