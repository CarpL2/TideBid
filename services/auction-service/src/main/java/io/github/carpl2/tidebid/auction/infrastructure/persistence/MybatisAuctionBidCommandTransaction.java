package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionProxyBidRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommand;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.AuctionOutboxEventFactory;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionOutboxRepository;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionSessionMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Writes command, proxy state, public bids and outbox rows in one local transaction. */
@Component
@Profile({"local-db", "nacos"})
public class MybatisAuctionBidCommandTransaction implements AuctionBidCommandTransaction {

    private final AuctionSessionMapper sessionMapper;
    private final AuctionSessionRepository sessionRepository;
    private final AuctionProxyBidRepository proxyBidRepository;
    private final AuctionBidCommandRepository commandRepository;
    private final JdbcAuctionOutboxRepository outboxRepository;
    private final AuctionOutboxEventFactory eventFactory;

    public MybatisAuctionBidCommandTransaction(
            AuctionSessionMapper sessionMapper,
            AuctionSessionRepository sessionRepository,
            AuctionProxyBidRepository proxyBidRepository,
            AuctionBidCommandRepository commandRepository,
            JdbcAuctionOutboxRepository outboxRepository,
            AuctionOutboxEventFactory eventFactory
    ) {
        this.sessionMapper = sessionMapper;
        this.sessionRepository = sessionRepository;
        this.proxyBidRepository = proxyBidRepository;
        this.commandRepository = commandRepository;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
    }

    @Override
    @Transactional
    public CommittedCommand commit(CommitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        try {
            commandRepository.insert(request.processingCommand());
            applyProxyMutation(request.proxyMutation());
            for (int index = 0; index < request.bids().size(); index++) {
                BidRecord bid = request.bids().get(index);
                long expectedVersion = request.expectedSessionVersion() + index;
                int updated = sessionMapper.acceptBid(
                        bid.auctionId(), bid.bidderId(), bid.amount(), bid.previousPrice(), bid.sequenceNo(),
                        expectedVersion, bid.createdAt());
                if (updated != 1) {
                    throw new AuctionBidCommandTransaction.BidConflictException();
                }
                BidRecord storedBid = sessionRepository.insertBid(bid);
                outboxRepository.enqueue(eventFactory.bidAccepted(storedBid), storedBid.createdAt());
            }
            if (!commandRepository.update(request.completedCommand())) {
                throw new AuctionBidCommandTransaction.BidConflictException();
            }
            return new CommittedCommand(request.completedCommand(), request.bids());
        } catch (DuplicateKeyException exception) {
            throw new DuplicateCommandException(exception);
        } catch (DeadlockLoserDataAccessException exception) {
            // InnoDB can deadlock when two commands hold command-row FK locks and then
            // compete for the same auction session. Treat it like a CAS conflict so the
            // bounded coordinator can reload and re-plan.
            throw new AuctionBidCommandTransaction.BidConflictException();
        }
    }

    private void applyProxyMutation(ProxyMutation mutation) {
        switch (mutation.type()) {
            case NONE -> { }
            case INSERT -> proxyBidRepository.insert(mutation.proxyBid());
            case UPDATE -> {
                if (!proxyBidRepository.update(mutation.proxyBid())) {
                    throw new AuctionBidCommandTransaction.BidConflictException();
                }
            }
        }
    }

}
