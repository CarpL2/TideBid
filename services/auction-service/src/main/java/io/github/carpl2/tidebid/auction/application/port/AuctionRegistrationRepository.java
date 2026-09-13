package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;

import java.util.Optional;
import java.util.List;

public interface AuctionRegistrationRepository {

    AuctionRegistration insert(AuctionRegistration registration);

    Optional<AuctionRegistration> findById(long registrationId);

    Optional<AuctionRegistration> findByRegistrationNo(String registrationNo);

    Optional<AuctionRegistration> findByAuctionAndBidder(long auctionId, long bidderId);

    RegistrationPage findByBidder(long bidderId, int offset, int limit);

    record RegistrationPage(List<AuctionRegistration> registrations, long total) {
        public RegistrationPage {
            registrations = List.copyOf(registrations);
            if (total < registrations.size()) {
                throw new IllegalArgumentException("total must not be below the returned registration count");
            }
        }
    }
}
