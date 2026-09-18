package io.github.carpl2.tidebid.account.application;

import io.github.carpl2.tidebid.account.application.port.SellerCreditTransaction;
import io.github.carpl2.tidebid.contracts.SellerCreditRequestedEvent;
import io.github.carpl2.tidebid.contracts.SellerCreditedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
@Profile({"local-db", "nacos"})
public class SellerCreditService {

    private final SellerCreditTransaction transaction;

    public SellerCreditService(SellerCreditTransaction transaction) {
        this.transaction = transaction;
    }

    public SellerCreditedEvent credit(
            UUID sourceEventId,
            String traceId,
            SellerCreditRequestedEvent request
    ) {
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        return transaction.credit(sourceEventId, traceId, request);
    }
}
