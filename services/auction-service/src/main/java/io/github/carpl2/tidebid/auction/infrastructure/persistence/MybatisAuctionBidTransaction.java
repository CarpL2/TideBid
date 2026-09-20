package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.application.port.AuctionBidTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionAntiSnipingCalculator;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionTimingProperties;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionSessionMapper;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.AuctionOutboxEventFactory;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionOutboxRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile({"local-db", "nacos"})
public class MybatisAuctionBidTransaction implements AuctionBidTransaction {

    private final AuctionSessionMapper sessionMapper;
    private final AuctionSessionRepository sessionRepository;
    private final JdbcAuctionOutboxRepository outboxRepository;
    private final AuctionOutboxEventFactory eventFactory;
    private final AuctionAntiSnipingCalculator antiSnipingCalculator;
    private final AuctionTimingProperties timingProperties;

    public MybatisAuctionBidTransaction(
            AuctionSessionMapper sessionMapper,
            AuctionSessionRepository sessionRepository,
            JdbcAuctionOutboxRepository outboxRepository,
            AuctionOutboxEventFactory eventFactory
    ) {
        this(sessionMapper, sessionRepository, outboxRepository, eventFactory, defaultTimingProperties());
    }

    @Autowired
    public MybatisAuctionBidTransaction(
            AuctionSessionMapper sessionMapper,
            AuctionSessionRepository sessionRepository,
            JdbcAuctionOutboxRepository outboxRepository,
            AuctionOutboxEventFactory eventFactory,
            AuctionTimingProperties timingProperties
    ) {
        this.sessionMapper = sessionMapper;
        this.sessionRepository = sessionRepository;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
        this.antiSnipingCalculator = new AuctionAntiSnipingCalculator();
        this.timingProperties = timingProperties;
    }

    @Override
    @Transactional
    public AcceptedBid accept(BidRecord bid, long expectedSessionVersion) {
        if (bid == null || expectedSessionVersion < 0) {
            throw new IllegalArgumentException("bid and expectedSessionVersion are required");
        }
        AuctionSession session = sessionRepository.findSessionById(bid.auctionId()).orElse(null);
        AuctionAntiSnipingCalculator.Result timing = session == null
                ? unchangedTiming(bid)
                : antiSnipingCalculator.calculate(
                        session, bid.createdAt(), true,
                        timingProperties.antiSnipingWindow(), timingProperties.antiSnipingExtension(),
                        timingProperties.maxTotalExtension());
        int updated = timing.extended()
                ? sessionMapper.acceptBidWithTiming(
                        bid.auctionId(), bid.bidderId(), bid.amount(), bid.previousPrice(), bid.sequenceNo(),
                        expectedSessionVersion, timing.endAt(), timing.extensionCount(), bid.createdAt())
                : sessionMapper.acceptBid(
                        bid.auctionId(), bid.bidderId(), bid.amount(), bid.previousPrice(), bid.sequenceNo(),
                        expectedSessionVersion, bid.createdAt());
        if (updated != 1) {
            throw new BidConflictException();
        }
        try {
            BidRecord storedBid = sessionRepository.insertBid(bid);
            outboxRepository.enqueue(eventFactory.bidAccepted(storedBid), storedBid.createdAt());
            if (timing.extended()) {
                outboxRepository.enqueue(eventFactory.auctionTimeExtended(
                        bid.auctionId(), timing.previousEndAt(), timing.endAt(), timing.extensionCount(),
                        bid.createdAt(), bid.requestId()), bid.createdAt());
                outboxRepository.enqueueIfAbsent(eventFactory.closeAuction(
                        bid.auctionId(), timing.endAt(), bid.createdAt()), bid.createdAt());
            }
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

    private static AuctionAntiSnipingCalculator.Result unchangedTiming(BidRecord bid) {
        return new AuctionAntiSnipingCalculator.Result(false, bid.createdAt(), bid.createdAt(), 0);
    }

    private static AuctionTimingProperties defaultTimingProperties() {
        return new AuctionTimingProperties(
                java.time.Duration.ofMinutes(1), java.time.Duration.ofDays(30), false,
                java.time.Duration.ofSeconds(10), 100);
    }
}
