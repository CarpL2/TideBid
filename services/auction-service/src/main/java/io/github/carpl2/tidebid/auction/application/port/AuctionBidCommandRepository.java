package io.github.carpl2.tidebid.auction.application.port;

import io.github.carpl2.tidebid.auction.domain.AuctionBidCommand;

import java.util.Optional;

public interface AuctionBidCommandRepository {
    AuctionBidCommand insert(AuctionBidCommand command);

    boolean update(AuctionBidCommand command);

    Optional<AuctionBidCommand> findByActorAndRequest(long actorId, String requestId);
}
