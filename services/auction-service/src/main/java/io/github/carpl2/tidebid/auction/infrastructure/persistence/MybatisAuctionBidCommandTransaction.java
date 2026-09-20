package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionProxyBidRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommand;
import io.github.carpl2.tidebid.auction.domain.AuctionAntiSnipingCalculator;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.AuctionOutboxEventFactory;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionOutboxRepository;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionTimingProperties;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionSessionMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AuctionAntiSnipingCalculator antiSnipingCalculator;
    private final AuctionTimingProperties timingProperties;

    public MybatisAuctionBidCommandTransaction(
            AuctionSessionMapper sessionMapper,
            AuctionSessionRepository sessionRepository,
            AuctionProxyBidRepository proxyBidRepository,
            AuctionBidCommandRepository commandRepository,
            JdbcAuctionOutboxRepository outboxRepository,
            AuctionOutboxEventFactory eventFactory
    ) {
        this(sessionMapper, sessionRepository, proxyBidRepository, commandRepository, outboxRepository,
                eventFactory, defaultTimingProperties());
    }

    @Autowired
    public MybatisAuctionBidCommandTransaction(
            AuctionSessionMapper sessionMapper,
            AuctionSessionRepository sessionRepository,
            AuctionProxyBidRepository proxyBidRepository,
            AuctionBidCommandRepository commandRepository,
            JdbcAuctionOutboxRepository outboxRepository,
            AuctionOutboxEventFactory eventFactory,
            AuctionTimingProperties timingProperties
    ) {
        this.sessionMapper = sessionMapper;
        this.sessionRepository = sessionRepository;
        this.proxyBidRepository = proxyBidRepository;
        this.commandRepository = commandRepository;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
        this.antiSnipingCalculator = new AuctionAntiSnipingCalculator();
        this.timingProperties = timingProperties;
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
            AuctionSession initialSession = request.bids().isEmpty()
                    ? null
                    : sessionRepository.findSessionById(request.processingCommand().auctionId()).orElse(null);
            AuctionAntiSnipingCalculator.Result timing = calculateTiming(initialSession, request.bids());
            for (int index = 0; index < request.bids().size(); index++) {
                BidRecord bid = request.bids().get(index);
                long expectedVersion = request.expectedSessionVersion() + index;
                boolean extend = timing.extended() && index == request.bids().size() - 1;
                int updated = extend
                        ? sessionMapper.acceptBidWithTiming(
                                bid.auctionId(), bid.bidderId(), bid.amount(), bid.previousPrice(), bid.sequenceNo(),
                                expectedVersion, timing.endAt(), timing.extensionCount(), bid.createdAt())
                        : sessionMapper.acceptBid(
                                bid.auctionId(), bid.bidderId(), bid.amount(), bid.previousPrice(), bid.sequenceNo(),
                                expectedVersion, bid.createdAt());
                if (updated != 1) {
                    throw new AuctionBidCommandTransaction.BidConflictException();
                }
                BidRecord storedBid = sessionRepository.insertBid(bid);
                outboxRepository.enqueue(eventFactory.bidAccepted(storedBid), storedBid.createdAt());
            }
            if (timing.extended()) {
                BidRecord trigger = request.bids().getLast();
                String traceId = request.processingCommand().requestId();
                outboxRepository.enqueue(eventFactory.auctionTimeExtended(
                        request.processingCommand().auctionId(), timing.previousEndAt(), timing.endAt(),
                        timing.extensionCount(), trigger.createdAt(), traceId), trigger.createdAt());
                outboxRepository.enqueueIfAbsent(eventFactory.closeAuction(
                        request.processingCommand().auctionId(), timing.endAt(), trigger.createdAt()), trigger.createdAt());
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

    private AuctionAntiSnipingCalculator.Result calculateTiming(
            AuctionSession session,
            java.util.List<BidRecord> bids
    ) {
        if (session == null || bids.isEmpty()) {
            return new AuctionAntiSnipingCalculator.Result(false,
                    session == null ? java.time.Instant.EPOCH : session.endAt(),
                    session == null ? java.time.Instant.EPOCH : session.endAt(),
                    session == null ? 0 : session.extensionCount());
        }
        return antiSnipingCalculator.calculate(
                session, bids.getLast().createdAt(), true,
                timingProperties.antiSnipingWindow(), timingProperties.antiSnipingExtension(),
                timingProperties.maxTotalExtension());
    }

    private static AuctionTimingProperties defaultTimingProperties() {
        return new AuctionTimingProperties(
                java.time.Duration.ofMinutes(1), java.time.Duration.ofDays(30), false,
                java.time.Duration.ofSeconds(10), 100);
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
