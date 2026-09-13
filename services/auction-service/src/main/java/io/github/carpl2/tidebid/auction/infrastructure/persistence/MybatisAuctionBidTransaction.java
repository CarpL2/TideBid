package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.application.port.AuctionBidTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionSessionMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile({"local-db", "nacos"})
public class MybatisAuctionBidTransaction implements AuctionBidTransaction {

    private final AuctionSessionMapper sessionMapper;
    private final AuctionSessionRepository sessionRepository;

    public MybatisAuctionBidTransaction(
            AuctionSessionMapper sessionMapper,
            AuctionSessionRepository sessionRepository
    ) {
        this.sessionMapper = sessionMapper;
        this.sessionRepository = sessionRepository;
    }

    @Override
    @Transactional
    public AcceptedBid accept(BidRecord bid, long expectedSessionVersion) {
        if (bid == null || expectedSessionVersion < 0) {
            throw new IllegalArgumentException("bid and expectedSessionVersion are required");
        }
        int updated = sessionMapper.acceptBid(
                bid.auctionId(),
                bid.bidderId(),
                bid.amount(),
                bid.previousPrice(),
                bid.sequenceNo(),
                expectedSessionVersion,
                bid.createdAt()
        );
        if (updated != 1) {
            throw new BidConflictException();
        }
        try {
            BidRecord storedBid = sessionRepository.insertBid(bid);
            return new AcceptedBid(
                    sessionRepository.findSessionById(bid.auctionId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Auction session disappeared after accepting a bid"
                            )),
                    storedBid
            );
        } catch (DuplicateKeyException exception) {
            throw new DuplicateBidException(exception);
        }
    }
}
