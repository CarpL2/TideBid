package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;

import java.time.Instant;
import java.util.List;

public interface AuctionRegistrationRecoveryTransaction {

    List<AuctionRegistration> claimDue(
            Instant now,
            String leaseOwner,
            Instant leaseUntil,
            int batchSize
    );
}
