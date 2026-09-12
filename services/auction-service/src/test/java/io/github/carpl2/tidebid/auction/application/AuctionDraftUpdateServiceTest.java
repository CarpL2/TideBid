package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionDraftTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
import io.github.carpl2.tidebid.auction.infrastructure.config.AuctionTimingProperties;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionDraftUpdateServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T07:00:00.123456789Z");
    private static final long SELLER_ID = 42L;
    private static final long ITEM_ID = 101L;

    private AuctionItemRepository itemRepository;
    private AuctionSessionRepository sessionRepository;
    private AuctionDraftTransaction transaction;
    private AuctionDraftUpdateService service;

    @BeforeEach
    void setUp() {
        itemRepository = mock(AuctionItemRepository.class);
        sessionRepository = mock(AuctionSessionRepository.class);
        transaction = mock(AuctionDraftTransaction.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AuctionDraftUpdateService(
                itemRepository,
                sessionRepository,
                transaction,
                new AuctionDraftFieldsValidator(
                        new AuctionTimingProperties(
                                Duration.ofMinutes(1), Duration.ofDays(7), true, Duration.ofSeconds(1), 50),
                        clock
                ),
                clock
        );
    }

    @Test
    void updatesOwnedDraftAndNormalizesEditableFields() {
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(item(
                SELLER_ID, AuctionItemReviewStatus.DRAFT, 3L
        )));
        when(sessionRepository.findSessionByItemId(ITEM_ID)).thenReturn(Optional.of(session(
                SELLER_ID, AuctionSessionStatus.DRAFT, 5L
        )));
        when(transaction.update(any(), any())).thenAnswer(invocation ->
                new AuctionDraftTransaction.UpdatedDraft(invocation.getArgument(0), invocation.getArgument(1))
        );

        service.update(validCommand(SELLER_ID, 3L, 5L));

        ArgumentCaptor<AuctionItem> itemCaptor = ArgumentCaptor.forClass(AuctionItem.class);
        ArgumentCaptor<AuctionSession> sessionCaptor = ArgumentCaptor.forClass(AuctionSession.class);
        verify(transaction).update(itemCaptor.capture(), sessionCaptor.capture());
        assertThat(itemCaptor.getValue().title()).isEqualTo("Updated keyboard");
        assertThat(itemCaptor.getValue().category()).isEqualTo("ELECTRONICS");
        assertThat(itemCaptor.getValue().reviewStatus()).isEqualTo(AuctionItemReviewStatus.DRAFT);
        assertThat(itemCaptor.getValue().version()).isEqualTo(3L);
        assertThat(sessionCaptor.getValue().startPrice()).isEqualByComparingTo("120.00");
        assertThat(sessionCaptor.getValue().version()).isEqualTo(5L);
        assertThat(itemCaptor.getValue().updatedAt()).isEqualTo(NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void allowsRejectedDraftAndPreservesReviewHistory() {
        AuctionItem rejected = item(SELLER_ID, AuctionItemReviewStatus.REJECTED, 7L);
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(rejected));
        when(sessionRepository.findSessionByItemId(ITEM_ID)).thenReturn(Optional.of(session(
                SELLER_ID, AuctionSessionStatus.DRAFT, 2L
        )));
        when(transaction.update(any(), any())).thenAnswer(invocation ->
                new AuctionDraftTransaction.UpdatedDraft(invocation.getArgument(0), invocation.getArgument(1))
        );

        service.update(validCommand(SELLER_ID, 7L, 2L));

        ArgumentCaptor<AuctionItem> captor = ArgumentCaptor.forClass(AuctionItem.class);
        verify(transaction).update(captor.capture(), any());
        assertThat(captor.getValue().reviewStatus()).isEqualTo(AuctionItemReviewStatus.REJECTED);
        assertThat(captor.getValue().submissionVersion()).isEqualTo(rejected.submissionVersion());
        assertThat(captor.getValue().submittedAt()).isEqualTo(rejected.submittedAt());
    }

    @Test
    void rejectsMissingForeignOrLockedItemBeforeStartingTransaction() {
        when(itemRepository.findItemById(ITEM_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(item(99L, AuctionItemReviewStatus.DRAFT, 0L)))
                .thenReturn(Optional.of(item(SELLER_ID, AuctionItemReviewStatus.PENDING_REVIEW, 0L)));

        assertError(AuctionErrorCode.ASSET_NOT_FOUND, () -> service.update(validCommand(SELLER_ID, 0L, 0L)));
        assertError(AuctionErrorCode.ASSET_ACCESS_DENIED, () -> service.update(validCommand(SELLER_ID, 0L, 0L)));
        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.update(validCommand(SELLER_ID, 0L, 0L)));

        verify(sessionRepository, never()).findSessionByItemId(anyLong());
        verify(transaction, never()).update(any(), any());
    }

    @Test
    void rejectsStaleItemOrSessionVersionAndNonDraftSession() {
        when(itemRepository.findItemById(ITEM_ID))
                .thenReturn(Optional.of(item(SELLER_ID, AuctionItemReviewStatus.DRAFT, 2L)))
                .thenReturn(Optional.of(item(SELLER_ID, AuctionItemReviewStatus.DRAFT, 1L)))
                .thenReturn(Optional.of(item(SELLER_ID, AuctionItemReviewStatus.DRAFT, 1L)));
        when(sessionRepository.findSessionByItemId(ITEM_ID))
                .thenReturn(Optional.of(session(SELLER_ID, AuctionSessionStatus.DRAFT, 1L)))
                .thenReturn(Optional.of(session(SELLER_ID, AuctionSessionStatus.DRAFT, 2L)))
                .thenReturn(Optional.of(session(SELLER_ID, AuctionSessionStatus.OPEN, 1L)));

        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.update(validCommand(SELLER_ID, 1L, 1L)));
        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.update(validCommand(SELLER_ID, 1L, 1L)));
        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.update(validCommand(SELLER_ID, 1L, 1L)));

        verify(transaction, never()).update(any(), any());
    }

    @Test
    void rejectsInvalidFieldsBeforeStartingTransaction() {
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(item(
                SELLER_ID, AuctionItemReviewStatus.DRAFT, 0L
        )));
        when(sessionRepository.findSessionByItemId(ITEM_ID)).thenReturn(Optional.of(session(
                SELLER_ID, AuctionSessionStatus.DRAFT, 0L
        )));
        AuctionDraftUpdateService.UpdateDraftCommand invalid = new AuctionDraftUpdateService.UpdateDraftCommand(
                SELLER_ID, ITEM_ID, 0L, 0L,
                "Updated keyboard", "A valid updated auction description", "electronics",
                AuctionItemCondition.LIKE_NEW,
                new BigDecimal("120.001"), new BigDecimal("10.00"), new BigDecimal("60.00"),
                NOW.plusSeconds(120), NOW.plusSeconds(3600)
        );

        assertError(AuctionErrorCode.AUCTION_AMOUNT_INVALID, () -> service.update(invalid));

        verify(transaction, never()).update(any(), any());
    }

    @Test
    void mapsAtomicCasConflictToPublicStateConflict() {
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(item(
                SELLER_ID, AuctionItemReviewStatus.DRAFT, 0L
        )));
        when(sessionRepository.findSessionByItemId(ITEM_ID)).thenReturn(Optional.of(session(
                SELLER_ID, AuctionSessionStatus.DRAFT, 0L
        )));
        when(transaction.update(any(), any())).thenThrow(
                new AuctionDraftTransaction.DraftUpdateConflictException("changed concurrently")
        );

        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.update(validCommand(SELLER_ID, 0L, 0L)));
    }

    private static AuctionDraftUpdateService.UpdateDraftCommand validCommand(
            long sellerId,
            long itemVersion,
            long sessionVersion
    ) {
        return new AuctionDraftUpdateService.UpdateDraftCommand(
                sellerId, ITEM_ID, itemVersion, sessionVersion,
                "  Updated keyboard  ", "A valid updated auction description", " electronics ",
                AuctionItemCondition.LIKE_NEW,
                new BigDecimal("120"), new BigDecimal("10"), new BigDecimal("60"),
                NOW.plusSeconds(120), NOW.plusSeconds(3600)
        );
    }

    private static AuctionItem item(long sellerId, AuctionItemReviewStatus status, long version) {
        boolean draft = status == AuctionItemReviewStatus.DRAFT;
        return new AuctionItem(
                ITEM_ID, sellerId,
                "Mechanical keyboard", "An existing auction item description", "ELECTRONICS",
                AuctionItemCondition.GOOD, status,
                draft ? 0 : 1, version,
                draft ? null : NOW.minus(Duration.ofMinutes(30)),
                status == AuctionItemReviewStatus.APPROVED ? NOW.minus(Duration.ofMinutes(20)) : null,
                NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofMinutes(10))
        );
    }

    private static AuctionSession session(long sellerId, AuctionSessionStatus status, long version) {
        return new AuctionSession(
                201L, ITEM_ID, sellerId,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0,
                NOW.plusSeconds(120), NOW.plusSeconds(3600),
                status, version, NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofMinutes(10))
        );
    }

    private static void assertError(AuctionErrorCode errorCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(errorCode));
    }
}
