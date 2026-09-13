package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationCreationTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionRegistrationRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistration;
import io.github.carpl2.tidebid.auction.domain.AuctionRegistrationStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionRegistrationRecoveryProperties;
import io.github.carpl2.tidebid.core.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionRegistrationCreationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T06:00:00.123456789Z");
    private static final Instant STORED_NOW = Instant.parse("2026-09-13T06:00:00.123456Z");
    private static final long AUCTION_ID = 101L;
    private static final long ITEM_ID = 201L;
    private static final long SELLER_ID = 301L;
    private static final long BIDDER_ID = 401L;
    private static final long REGISTRATION_ID = 501L;

    private AuctionSessionRepository sessionRepository;
    private AuctionItemRepository itemRepository;
    private AuctionRegistrationRepository registrationRepository;
    private AuctionRegistrationCreationTransaction creationTransaction;
    private AuctionSessionLifecycleService lifecycleService;
    private IdGenerator idGenerator;
    private AuctionRegistrationCreationService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AuctionSessionRepository.class);
        itemRepository = mock(AuctionItemRepository.class);
        registrationRepository = mock(AuctionRegistrationRepository.class);
        creationTransaction = mock(AuctionRegistrationCreationTransaction.class);
        lifecycleService = mock(AuctionSessionLifecycleService.class);
        idGenerator = mock(IdGenerator.class);
        service = new AuctionRegistrationCreationService(
                sessionRepository,
                itemRepository,
                registrationRepository,
                creationTransaction,
                lifecycleService,
                idGenerator,
                new AuctionRegistrationRecoveryProperties(
                        Duration.ofSeconds(5), Duration.ofMinutes(5), Duration.ofSeconds(30),
                        Duration.ofSeconds(2), 50, 8
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createsAStablePendingHoldBeforeTheAuctionStarts() {
        AuctionSession session = scheduledSession(STORED_NOW.plusSeconds(60));
        arrangeVisibleAuction(session, approvedItem());
        when(registrationRepository.findByAuctionAndBidder(AUCTION_ID, BIDDER_ID)).thenReturn(Optional.empty());
        when(idGenerator.nextId()).thenReturn(REGISTRATION_ID);
        AuctionRegistration storedPending = pendingRegistration(STORED_NOW);
        when(creationTransaction.createPending(any())).thenReturn(storedPending);

        AuctionRegistrationCreationService.RegistrationCreationResult result = service.createPending(
                new AuctionRegistrationCreationService.CreateRegistrationCommand(BIDDER_ID, AUCTION_ID)
        );

        assertThat(result.created()).isTrue();
        ArgumentCaptor<AuctionRegistration> captor = ArgumentCaptor.forClass(AuctionRegistration.class);
        verify(creationTransaction).createPending(captor.capture());
        AuctionRegistration pending = captor.getValue();
        assertThat(result.registration()).isSameAs(storedPending);
        assertThat(pending.id()).isEqualTo(REGISTRATION_ID);
        assertThat(pending.registrationNo()).isEqualTo("REGISTRATION:" + REGISTRATION_ID);
        assertThat(pending.auctionId()).isEqualTo(AUCTION_ID);
        assertThat(pending.bidderId()).isEqualTo(BIDDER_ID);
        assertThat(pending.depositAmount()).isEqualByComparingTo("50.00");
        assertThat(pending.status()).isEqualTo(AuctionRegistrationStatus.PENDING_HOLD);
        assertThat(pending.attemptCount()).isZero();
        assertThat(pending.nextRetryAt()).isEqualTo(STORED_NOW.plusSeconds(5));
        assertThat(pending.lastAttemptAt()).isNull();
        assertThat(pending.createdAt()).isEqualTo(STORED_NOW);
        assertThat(pending.updatedAt()).isEqualTo(STORED_NOW);
    }

    @Test
    void returnsAnExistingLogicalRegistrationWithoutAllocatingAnotherId() {
        AuctionSession open = withStatus(scheduledSession(STORED_NOW.minusSeconds(1)), AuctionSessionStatus.OPEN);
        AuctionRegistration existing = registration(AuctionRegistrationStatus.REGISTERED);
        arrangeVisibleAuction(open, approvedItem());
        when(registrationRepository.findByAuctionAndBidder(AUCTION_ID, BIDDER_ID))
                .thenReturn(Optional.of(existing));

        AuctionRegistrationCreationService.RegistrationCreationResult result = service.createPending(
                new AuctionRegistrationCreationService.CreateRegistrationCommand(BIDDER_ID, AUCTION_ID)
        );

        assertThat(result.registration()).isSameAs(existing);
        assertThat(result.created()).isFalse();
        verify(idGenerator, never()).nextId();
        verify(creationTransaction, never()).createPending(any());
    }

    @Test
    void reloadsTheConcurrentWinnerAfterTheUniqueConstraintRejectsItsInsert() {
        AuctionSession session = scheduledSession(STORED_NOW.plusSeconds(60));
        AuctionRegistration winner = registration(AuctionRegistrationStatus.PENDING_HOLD);
        arrangeVisibleAuction(session, approvedItem());
        when(registrationRepository.findByAuctionAndBidder(AUCTION_ID, BIDDER_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(idGenerator.nextId()).thenReturn(REGISTRATION_ID + 1);
        when(creationTransaction.createPending(any())).thenThrow(
                new AuctionRegistrationCreationTransaction.DuplicateRegistrationException(
                        new IllegalStateException("duplicate")
                )
        );

        AuctionRegistrationCreationService.RegistrationCreationResult result = service.createPending(
                new AuctionRegistrationCreationService.CreateRegistrationCommand(BIDDER_ID, AUCTION_ID)
        );

        assertThat(result.registration()).isSameAs(winner);
        assertThat(result.created()).isFalse();
    }

    @Test
    void rejectsTheSellerBeforeLookingForOrCreatingARegistration() {
        AuctionSession session = scheduledSession(STORED_NOW.plusSeconds(60));
        arrangeVisibleAuction(session, approvedItem());

        assertError(
                AuctionErrorCode.SELLER_CANNOT_PARTICIPATE,
                () -> service.createPending(
                        new AuctionRegistrationCreationService.CreateRegistrationCommand(SELLER_ID, AUCTION_ID)
                )
        );

        verify(registrationRepository, never()).findByAuctionAndBidder(AUCTION_ID, SELLER_ID);
        verify(creationTransaction, never()).createPending(any());
    }

    @Test
    void closesNewRegistrationAtTheExactStartInstant() {
        AuctionSession session = scheduledSession(STORED_NOW);
        arrangeVisibleAuction(session, approvedItem());
        when(registrationRepository.findByAuctionAndBidder(AUCTION_ID, BIDDER_ID)).thenReturn(Optional.empty());

        assertError(
                AuctionErrorCode.REGISTRATION_CLOSED,
                () -> service.createPending(
                        new AuctionRegistrationCreationService.CreateRegistrationCommand(BIDDER_ID, AUCTION_ID)
                )
        );

        verify(idGenerator, never()).nextId();
        verify(creationTransaction, never()).createPending(any());
    }

    @Test
    void hidesDraftOrUnapprovedAuctions() {
        AuctionSession draft = withStatus(scheduledSession(STORED_NOW.plusSeconds(60)), AuctionSessionStatus.DRAFT);
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(draft));
        when(lifecycleService.advanceToCurrentState(draft)).thenReturn(draft);
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(item(AuctionItemReviewStatus.DRAFT)));

        assertError(
                AuctionErrorCode.AUCTION_NOT_FOUND,
                () -> service.createPending(
                        new AuctionRegistrationCreationService.CreateRegistrationCommand(BIDDER_ID, AUCTION_ID)
                )
        );

        verify(registrationRepository, never()).findByAuctionAndBidder(AUCTION_ID, BIDDER_ID);
    }

    @Test
    void validatesTheCommandAndMissingAuction() {
        assertError(AuctionErrorCode.AUCTION_INVALID, () -> service.createPending(null));
        assertError(
                AuctionErrorCode.AUCTION_INVALID,
                () -> service.createPending(
                        new AuctionRegistrationCreationService.CreateRegistrationCommand(0L, AUCTION_ID)
                )
        );
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.empty());
        assertError(
                AuctionErrorCode.AUCTION_NOT_FOUND,
                () -> service.createPending(
                        new AuctionRegistrationCreationService.CreateRegistrationCommand(BIDDER_ID, AUCTION_ID)
                )
        );
    }

    private void arrangeVisibleAuction(AuctionSession session, AuctionItem item) {
        when(sessionRepository.findSessionById(AUCTION_ID)).thenReturn(Optional.of(session));
        when(lifecycleService.advanceToCurrentState(session)).thenReturn(session);
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(item));
    }

    private static AuctionItem approvedItem() {
        return item(AuctionItemReviewStatus.APPROVED);
    }

    private static AuctionItem item(AuctionItemReviewStatus status) {
        boolean approved = status == AuctionItemReviewStatus.APPROVED;
        int submissionVersion = approved ? 1 : 0;
        long version = approved ? 2 : 0;
        Instant submittedAt = approved ? STORED_NOW.minusSeconds(120) : null;
        Instant approvedAt = status == AuctionItemReviewStatus.APPROVED ? STORED_NOW.minusSeconds(60) : null;
        return new AuctionItem(
                ITEM_ID, SELLER_ID, "Mechanical keyboard", "A sufficiently detailed description",
                "ELECTRONICS", AuctionItemCondition.GOOD, status, submissionVersion, version, submittedAt,
                approvedAt, STORED_NOW.minusSeconds(300), STORED_NOW.minusSeconds(60)
        );
    }

    private static AuctionSession scheduledSession(Instant startAt) {
        return new AuctionSession(
                AUCTION_ID, ITEM_ID, SELLER_ID,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0, startAt, startAt.plusSeconds(3600), AuctionSessionStatus.SCHEDULED, 2,
                STORED_NOW.minusSeconds(300), STORED_NOW.minusSeconds(60)
        );
    }

    private static AuctionSession withStatus(AuctionSession source, AuctionSessionStatus status) {
        return new AuctionSession(
                source.id(), source.itemId(), source.sellerId(), source.startPrice(), source.bidIncrement(),
                source.depositAmount(), source.currentPrice(), source.currentBidderId(), source.bidCount(),
                source.startAt(), source.endAt(), status, source.version(), source.createdAt(), source.updatedAt()
        );
    }

    private static AuctionRegistration registration(AuctionRegistrationStatus status) {
        return new AuctionRegistration(
                REGISTRATION_ID, "REGISTRATION:" + REGISTRATION_ID, AUCTION_ID, BIDDER_ID,
                new BigDecimal("50.00"), status, null, status == AuctionRegistrationStatus.REGISTERED ? 1 : 0,
                null, status == AuctionRegistrationStatus.REGISTERED ? STORED_NOW.minusSeconds(30) : null,
                null, null, status == AuctionRegistrationStatus.REGISTERED ? STORED_NOW.minusSeconds(20) : null,
                status == AuctionRegistrationStatus.REGISTERED ? 1 : 0,
                STORED_NOW.minusSeconds(60), STORED_NOW.minusSeconds(20)
        );
    }

    private static AuctionRegistration pendingRegistration(Instant now) {
        return new AuctionRegistration(
                REGISTRATION_ID, "REGISTRATION:" + REGISTRATION_ID, AUCTION_ID, BIDDER_ID,
                new BigDecimal("50.00"), AuctionRegistrationStatus.PENDING_HOLD, null, 0,
                now.plusSeconds(5), null, null, null, null, 0, now, now
        );
    }

    private static void assertError(AuctionErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(code));
    }
}
