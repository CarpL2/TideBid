package io.github.carpl2.tidebid.account.application.port;

import io.github.carpl2.tidebid.contracts.DepositSettlementRequestedEvent;
import io.github.carpl2.tidebid.contracts.WalletHoldSettledEvent;

import java.util.UUID;

public interface DepositSettlementTransaction {

    WalletHoldSettledEvent settle(
            UUID sourceEventId,
            String traceId,
            DepositSettlementRequestedEvent request
    );
}
