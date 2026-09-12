package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;

import java.util.Optional;

public interface AuctionRegistrationRepository {

    AuctionRegistration insert(AuctionRegistration registration);

    Optional<AuctionRegistration> findById(long registrationId);

    Optional<AuctionRegistration> findByRegistrationNo(String registrationNo);

    Optional<AuctionRegistration> findByAuctionAndBidder(long auctionId, long bidderId);
}
