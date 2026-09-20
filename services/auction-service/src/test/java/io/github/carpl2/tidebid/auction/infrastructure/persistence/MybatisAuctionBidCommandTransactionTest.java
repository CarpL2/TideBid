package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionBidCommandTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionProxyBidRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommand;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionBidCommandType;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.auction.domain.BidSource;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.AuctionOutboxEventFactory;
import io.github.carpl2.tidebid.auction.infrastructure.messaging.JdbcAuctionOutboxRepository;
import io.github.carpl2.tidebid.auction.infrastructure.persistence.mapper.AuctionSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static java.util.Optional.of;

class MybatisAuctionBidCommandTransactionTest {

    private static final Instant NOW = Instant.parse("2026-09-21T01:00:00Z");
    private static final String HASH = "a".repeat(64);

    private AuctionSessionMapper sessionMapper;
    private AuctionSessionRepository sessionRepository;
    private AuctionProxyBidRepository proxyBidRepository;
    private AuctionBidCommandRepository commandRepository;
    private JdbcAuctionOutboxRepository outboxRepository;
    private AuctionOutboxEventFactory eventFactory;
    private MybatisAuctionBidCommandTransaction transaction;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(AuctionSessionMapper.class);
        sessionRepository = mock(AuctionSessionRepository.class);
        proxyBidRepository = mock(AuctionProxyBidRepository.class);
        commandRepository = mock(AuctionBidCommandRepository.class);
        outboxRepository = mock(JdbcAuctionOutboxRepository.class);
        eventFactory = mock(AuctionOutboxEventFactory.class);
        transaction = new MybatisAuctionBidCommandTransaction(
                sessionMapper, sessionRepository, proxyBidRepository, commandRepository,
                outboxRepository, eventFactory
        );
    }

    @Test
    void commitsCommandBidAndOutboxInOneOrderedBoundary() {
        AuctionBidCommand processing = processingCommand();
        AuctionBidCommand completed = completedCommand();
        BidRecord bid = bid(8L);
        JdbcAuctionOutboxRepository.NewOutboxEvent event = new JdbcAuctionOutboxRepository.NewOutboxEvent(
                "event-1", "AUCTION", "1", "auction.bid-accepted", 1,
                "tidebid-auction-events", "{}", HASH, NOW
        );
        when(sessionMapper.acceptBid(1L, 101L, new BigDecimal("200.00"),
                new BigDecimal("120.00"), 8L, 4L, NOW)).thenReturn(1);
        when(sessionRepository.insertBid(bid)).thenReturn(bid);
        when(eventFactory.bidAccepted(bid)).thenReturn(event);
        when(commandRepository.update(completed)).thenReturn(true);

        AuctionBidCommandTransaction.CommittedCommand result = transaction.commit(
                new AuctionBidCommandTransaction.CommitRequest(
                        processing, completed, null, java.util.List.of(bid), 4L
                )
        );

        assertThat(result.command()).isSameAs(completed);
        assertThat(result.bids()).containsExactly(bid);
        var ordered = inOrder(commandRepository, sessionMapper, sessionRepository, outboxRepository);
        ordered.verify(commandRepository).insert(processing);
        ordered.verify(sessionMapper).acceptBid(1L, 101L, new BigDecimal("200.00"),
                new BigDecimal("120.00"), 8L, 4L, NOW);
        ordered.verify(sessionRepository).insertBid(bid);
        ordered.verify(outboxRepository).enqueue(event, NOW);
        ordered.verify(commandRepository).update(completed);
    }

    @Test
    void casConflictStopsBeforeBidOutboxAndCommandCompletion() {
        AuctionBidCommand processing = processingCommand();
        AuctionBidCommand completed = completedCommand();
        BidRecord bid = bid(8L);
        when(sessionMapper.acceptBid(anyLong(), anyLong(), any(BigDecimal.class), any(BigDecimal.class),
                anyLong(), anyLong(), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> transaction.commit(
                new AuctionBidCommandTransaction.CommitRequest(
                        processing, completed, null, java.util.List.of(bid), 4L
                )
                )).isInstanceOf(AuctionBidCommandTransaction.BidConflictException.class);

        verify(commandRepository).insert(processing);
        verify(sessionRepository, never()).insertBid(any());
        verify(outboxRepository, never()).enqueue(any(), any());
        verify(commandRepository, never()).update(any());
    }

    @Test
    void acceptedBidNearDeadlineUpdatesTimingAndWritesExtensionAndNewCloseCommand() {
        AuctionBidCommand processing = processingCommand();
        AuctionBidCommand completed = completedCommand();
        BidRecord bid = bid(8L);
        AuctionSession session = new AuctionSession(
                1L, 2L, 3L, new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("50.00"), new BigDecimal("120.00"), 101L, 7L,
                NOW.minusSeconds(3600), NOW.plusSeconds(30), AuctionSessionStatus.OPEN, 4L,
                NOW.minusSeconds(3600), NOW.minusSeconds(3600));
        JdbcAuctionOutboxRepository.NewOutboxEvent bidEvent = event("bid-event", NOW);
        JdbcAuctionOutboxRepository.NewOutboxEvent extensionEvent = event("extension-event", NOW);
        JdbcAuctionOutboxRepository.NewOutboxEvent closeEvent = event("close-event", NOW.plusSeconds(60));
        when(sessionRepository.findSessionById(1L)).thenReturn(of(session));
        when(sessionMapper.acceptBidWithTiming(1L, 101L, new BigDecimal("200.00"),
                new BigDecimal("120.00"), 8L, 4L, NOW.plusSeconds(60), 1, NOW)).thenReturn(1);
        when(sessionRepository.insertBid(bid)).thenReturn(bid);
        when(eventFactory.bidAccepted(bid)).thenReturn(bidEvent);
        when(eventFactory.auctionTimeExtended(1L, NOW.plusSeconds(30), NOW.plusSeconds(60), 1, NOW,
                "request_0001")).thenReturn(extensionEvent);
        when(eventFactory.closeAuction(1L, NOW.plusSeconds(60), NOW)).thenReturn(closeEvent);
        when(commandRepository.update(completed)).thenReturn(true);

        transaction.commit(new AuctionBidCommandTransaction.CommitRequest(
                processing, completed, null, java.util.List.of(bid), 4L));

        verify(sessionMapper).acceptBidWithTiming(1L, 101L, new BigDecimal("200.00"),
                new BigDecimal("120.00"), 8L, 4L, NOW.plusSeconds(60), 1, NOW);
        verify(outboxRepository).enqueue(bidEvent, NOW);
        verify(outboxRepository).enqueue(extensionEvent, NOW);
        verify(outboxRepository).enqueueIfAbsent(closeEvent, NOW);
    }

    private static JdbcAuctionOutboxRepository.NewOutboxEvent event(String id, Instant deliverAt) {
        return new JdbcAuctionOutboxRepository.NewOutboxEvent(
                id, "AUCTION", "1", "event", 1, "topic", "{}", HASH, deliverAt);
    }

    private static AuctionBidCommand processingCommand() {
        return new AuctionBidCommand(
                77L, 1L, 101L, "request_0001", AuctionBidCommandType.MANUAL_BID, HASH,
                AuctionBidCommandStatus.PROCESSING, null, null, null, null, null, NOW, null
        );
    }

    private static AuctionBidCommand completedCommand() {
        return new AuctionBidCommand(
                77L, 1L, 101L, "request_0001", AuctionBidCommandType.MANUAL_BID, HASH,
                AuctionBidCommandStatus.SUCCEEDED, 1, new BigDecimal("200.00"), true,
                8L, 8L, NOW, NOW.plusSeconds(1)
        );
    }

    private static BidRecord bid(long sequenceNo) {
        return new BidRecord(
                88L, 1L, 101L, "request_0001", BidSource.MANUAL, 77L,
                new BigDecimal("200.00"), new BigDecimal("120.00"), sequenceNo, NOW
        );
    }
}
