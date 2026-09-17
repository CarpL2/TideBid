package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.application.port.AuctionBidTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionSessionMapper;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.AuctionOutboxEventFactory;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionOutboxRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile({"local-db", "nacos"})
public class MybatisAuctionBidTransaction implements AuctionBidTransaction {

    private final AuctionSessionMapper sessionMapper;
    private final AuctionSessionRepository sessionRepository;
    private final JdbcAuctionOutboxRepository outboxRepository;
    private final AuctionOutboxEventFactory eventFactory;

    public MybatisAuctionBidTransaction(
            AuctionSessionMapper sessionMapper,
            AuctionSessionRepository sessionRepository,
            JdbcAuctionOutboxRepository outboxRepository,
            AuctionOutboxEventFactory eventFactory
    ) {
        this.sessionMapper = sessionMapper;
        this.sessionRepository = sessionRepository;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
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
            outboxRepository.enqueue(eventFactory.bidAccepted(storedBid), storedBid.createdAt());
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
