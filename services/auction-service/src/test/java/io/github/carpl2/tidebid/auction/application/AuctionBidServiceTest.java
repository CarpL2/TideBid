package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionBidTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.domain.BidRecord;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionBidServiceTest {

    private static final long AUCTION_ID = 101L;
    private static final long BIDDER_ID = 201L;
    private static final long BID_ID = 301L;
    private static final String REQUEST_ID = "manual-bid-0001";
    private static final Instant NOW = Instant.parse("2026-09-14T03:00:00.123456789Z");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-14T03:00:00.123456Z");

    private AuctionSessionRepository sessionRepository;
    private AuctionRegistrationRepository registrationRepository;
    private AuctionSessionLifecycleService lifecycleService;
    private AuctionBidTransaction bidTransaction;
    private IdGenerator idGenerator;
    private AuctionBidService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AuctionSessionRepository.class);
        registrationRepository = mock(AuctionRegistrationRepository.class);
        lifecycleService = mock(AuctionSessionLifecycleService.class);
        bidTransaction = mock(AuctionBidTransaction.class);
        idGenerator = mock(IdGenerator.class);
        service = new AuctionBidService(
                sessionRepository,
                registrationRepository,
                lifecycleService,
                bidTransaction,
                idGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void validatesThenAcceptsANormalizedBid() {
        AuctionSession opened = session(null, 0L, 7L);
        AuctionRegistration registered = registration();
        arrangeNewBid(opened, registered);
        when(idGenerator.nextId()).thenReturn(BID_ID);
        when(bidTransaction.accept(any(), anyLong())).thenAnswer(invocation -> {
            BidRecord candidate = invocation.getArgument(0);
            return new AuctionBidTransaction.AcceptedBid(
                    session(candidate.amount(), candidate.sequenceNo(), 8L), candidate
            );
        });

        BidRecord result = service.place(command(AUCTION_ID, new BigDecimal("100")));

        assertThat(result.id()).isEqualTo(BID_ID);
        assertThat(result.amount()).isEqualTo(new BigDecimal("100.00"));
        assertThat(result.previousPrice()).isNull();
        assertThat(result.sequenceNo()).isEqualTo(1L);
        assertThat(result.createdAt()).isEqualTo(ACCEPTED_AT);
        ArgumentCaptor<BidRecord> bidCaptor = ArgumentCaptor.forClass(BidRecord.class);
        verify(bidTransaction).accept(bidCaptor.capture(), org.mockito.ArgumentMatchers.eq(7L));
        assertThat(bidCaptor.getValue()).isEqualTo(result);
        verify(lifecycleService).advanceToCurrentState(opened);
    }

    @Test
    void returnsTheOriginalBidForAnIdempotentRetryWithoutReadingTheAuction() {
        BidRecord existing = bid(BID_ID, AUCTION_ID, BIDDER_ID, REQUEST_ID, "120.00", "100.00", 2L);
        when(sessionRepository.findBid(BIDDER_ID, REQUEST_ID)).thenReturn(Optional.of(existing));

        assertThat(service.place(command(AUCTION_ID, new BigDecimal("120.0")))).isSameAs(existing);

        verify(sessionRepository, never()).findSessionById(anyLong());
        verify(registrationRepository, never()).findByAuctionAndBidder(anyLong(), anyLong());
        verify(bidTransaction, never()).accept(any(), anyLong());
    }

    @Test
    void rejectsReusingARequestIdForDifferentPayload() {
        BidRecord existing = bid(BID_ID, AUCTION_ID, BIDDER_ID, REQUEST_ID, "120.00", "100.00", 2L);
        when(sessionRepository.findBid(BIDDER_ID, REQUEST_ID)).thenReturn(Optional.of(existing));

        assertBusinessError(
                () -> service.place(command(AUCTION_ID + 1, new BigDecimal("120.00"))),
                AuctionErrorCode.IDEMPOTENCY_CONFLICT
        );
        assertBusinessError(
                () -> service.place(command(AUCTION_ID, new BigDecimal("121.00"))),
                AuctionErrorCode.IDEMPOTENCY_CONFLICT
        );
    }

    @Test
    void resolvesAConcurrentDuplicateInsertFromTheWinningIdempotencyRecord() {
        AuctionSession opened = session(null, 0L, 7L);
        AuctionRegistration registered = registration();
        BidRecord winner = bid(BID_ID + 1, AUCTION_ID, BIDDER_ID, REQUEST_ID, "100.00", null, 1L);
        when(sessionRepository.findBid(BIDDER_ID, REQUEST_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(opened));
        when(lifecycleService.advanceToCurrentState(opened)).thenReturn(opened);
        when(registrationRepository.findByAuctionAndBidder(AUCTION_ID, BIDDER_ID))
                .thenReturn(Optional.of(registered));
        when(idGenerator.nextId()).thenReturn(BID_ID);
        when(bidTransaction.accept(any(), anyLong())).thenThrow(
                new AuctionBidTransaction.DuplicateBidException(new RuntimeException("duplicate"))
        );

        assertThat(service.place(command(AUCTION_ID, new BigDecimal("100")))).isSameAs(winner);
    }

    @Test
    void exposesTheLatestDatabasePriceAfterLosingTheSessionCas() {
        AuctionSession original = session(null, 0L, 7L);
        AuctionSession latest = session(new BigDecimal("150.00"), 1L, 8L);
        arrangeNewBid(original, registration());
        when(sessionRepository.findSessionById(AUCTION_ID))
                .thenReturn(Optional.of(original))
                .thenReturn(Optional.of(latest));
        when(idGenerator.nextId()).thenReturn(BID_ID);
        when(bidTransaction.accept(any(), anyLong())).thenThrow(
                new AuctionBidTransaction.BidConflictException()
        );

        assertThatThrownBy(() -> service.place(command(AUCTION_ID, new BigDecimal("100.00"))))
                .isInstanceOfSatisfying(AuctionBidConflictException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(AuctionErrorCode.BID_CONFLICT);
                    assertThat(exception.snapshot().auctionId()).isEqualTo(AUCTION_ID);
                    assertThat(exception.snapshot().currentPrice()).isEqualByComparingTo("150.00");
                    assertThat(exception.snapshot().minimumNextBid()).isEqualByComparingTo("160.00");
                    assertThat(exception.snapshot().bidCount()).isEqualTo(1L);
                    assertThat(exception.snapshot().version()).isEqualTo(8L);
                });
    }

    @Test
    void resolvesAConcurrentSameRequestAfterLosingTheSessionCas() {
        AuctionSession original = session(null, 0L, 7L);
        BidRecord winner = bid(BID_ID + 1, AUCTION_ID, BIDDER_ID, REQUEST_ID, "100.00", null, 1L);
        arrangeNewBid(original, registration());
        when(sessionRepository.findBid(BIDDER_ID, REQUEST_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(idGenerator.nextId()).thenReturn(BID_ID);
        when(bidTransaction.accept(any(), anyLong())).thenThrow(
                new AuctionBidTransaction.BidConflictException()
        );

        assertThat(service.place(command(AUCTION_ID, new BigDecimal("100")))).isSameAs(winner);

        verify(sessionRepository, org.mockito.Mockito.times(1)).findSessionById(AUCTION_ID);
    }

    @Test
    void rejectsInvalidCommandBeforeAccessingPersistence() {
        assertBusinessError(
                () -> service.place(command(AUCTION_ID, new BigDecimal("1.001"))),
                AuctionErrorCode.BID_AMOUNT_INVALID
        );
        assertBusinessError(
                () -> service.place(new AuctionBidService.PlaceBidCommand(
                        BIDDER_ID, AUCTION_ID, "bad", new BigDecimal("100.00")
                )),
                AuctionErrorCode.AUCTION_INVALID
        );
        verify(sessionRepository, never()).findBid(anyLong(), any());
    }

    private void arrangeNewBid(AuctionSession opened, AuctionRegistration registration) {
        when(sessionRepository.findBid(BIDDER_ID, REQUEST_ID)).thenReturn(Optional.empty());
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(opened));
        when(lifecycleService.advanceToCurrentState(opened)).thenReturn(opened);
        when(registrationRepository.findByAuctionAndBidder(AUCTION_ID, BIDDER_ID))
                .thenReturn(Optional.of(registration));
    }

    private static AuctionBidService.PlaceBidCommand command(long auctionId, BigDecimal amount) {
        return new AuctionBidService.PlaceBidCommand(BIDDER_ID, auctionId, REQUEST_ID, amount);
    }

    private static AuctionSession session(BigDecimal currentPrice, long bidCount, long version) {
        return new AuctionSession(
                AUCTION_ID, 102L, 401L,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                currentPrice, bidCount == 0 ? null : 999L, bidCount,
                ACCEPTED_AT.minusSeconds(60), ACCEPTED_AT.plusSeconds(60),
                AuctionSessionStatus.OPEN, version,
                ACCEPTED_AT.minusSeconds(120), ACCEPTED_AT.minusSeconds(1)
        );
    }

    private static AuctionRegistration registration() {
        return new AuctionRegistration(
                501L, "REGISTRATION:501", AUCTION_ID, BIDDER_ID, new BigDecimal("50.00"),
                AuctionRegistrationStatus.REGISTERED, null, 1, null, ACCEPTED_AT.minusSeconds(30),
                null, null, ACCEPTED_AT.minusSeconds(30), 1L,
                ACCEPTED_AT.minusSeconds(60), ACCEPTED_AT.minusSeconds(30)
        );
    }

    private static BidRecord bid(
            long id,
            long auctionId,
            long bidderId,
            String requestId,
            String amount,
            String previousPrice,
            long sequenceNo
    ) {
        return new BidRecord(
                id, auctionId, bidderId, requestId, new BigDecimal(amount),
                previousPrice == null ? null : new BigDecimal(previousPrice), sequenceNo, ACCEPTED_AT
        );
    }

    private static void assertBusinessError(Runnable invocation, AuctionErrorCode expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(expected)
                );
    }
}
