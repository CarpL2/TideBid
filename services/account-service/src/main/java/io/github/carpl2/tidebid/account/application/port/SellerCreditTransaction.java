package io.github.carpl2.tidebid.account.application.port;

import io.github.carpl2.tidebid.contracts.SellerCreditRequestedEvent;
import io.github.carpl2.tidebid.contracts.SellerCreditedEvent;

import java.util.UUID;

public interface SellerCreditTransaction {

    SellerCreditedEvent credit(
            UUID sourceEventId,
            String traceId,
            SellerCreditRequestedEvent request
    );
}
