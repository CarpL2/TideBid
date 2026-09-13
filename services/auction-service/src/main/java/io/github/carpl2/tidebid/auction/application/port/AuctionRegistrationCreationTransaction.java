package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;

public interface AuctionRegistrationCreationTransaction {

    AuctionRegistration createPending(AuctionRegistration registration);

    final class DuplicateRegistrationException extends RuntimeException {
        public DuplicateRegistrationException(Throwable cause) {
            super("Auction registration already exists", cause);
        }
    }
}
