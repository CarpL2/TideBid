package io.github.carpl2.tidebid.auction.application;

import io.github.carpl2.tidebid.auction.application.port.AuctionItemRepository;
import io.github.carpl2.tidebid.auction.application.port.AuctionReviewTransaction;
import io.github.carpl2.tidebid.auction.application.port.AuctionSessionRepository;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import io.github.carpl2.tidebid.auction.domain.AuctionErrorCode;
import io.github.carpl2.tidebid.auction.domain.AuctionItem;
import io.github.carpl2.tidebid.auction.domain.AuctionItemCondition;
import io.github.carpl2.tidebid.auction.domain.AuctionItemReviewStatus;
import io.github.carpl2.tidebid.auction.domain.AuctionReview;
import io.github.carpl2.tidebid.auction.domain.AuctionReviewDecision;
import io.github.carpl2.tidebid.auction.domain.AuctionSession;
import io.github.carpl2.tidebid.auction.domain.AuctionSessionStatus;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuctionReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private static final long ITEM_ID = 101L;
    private static final long SELLER_ID = 42L;
    private static final long REVIEWER_ID = 99L;

    private AuctionItemRepository itemRepository;
    private AuctionSessionRepository sessionRepository;
    private AuctionReviewTransaction transaction;
    private IdGenerator idGenerator;
    private AuctionReviewService service;

    @BeforeEach
    void setUp() {
        itemRepository = mock(AuctionItemRepository.class);
        sessionRepository = mock(AuctionSessionRepository.class);
        transaction = mock(AuctionReviewTransaction.class);
        idGenerator = mock(IdGenerator.class);
        service = new AuctionReviewService(
                itemRepository, sessionRepository, transaction, idGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void approvesCurrentSubmissionAndTrimsOptionalComment() {
        AuctionItem item = pendingItem();
        AuctionSession session = draftSession(NOW.plusSeconds(3600));
        AuctionItem approved = approvedItem();
        AuctionSession scheduled = scheduledSession();
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(item));
        when(sessionRepository.findSessionByItemId(ITEM_ID)).thenReturn(Optional.of(session));
        when(idGenerator.nextId()).thenReturn(301L);
        when(transaction.approve(any(), eq(item.version()), eq(session))).thenAnswer(invocation -> {
            AuctionReview review = invocation.getArgument(0);
            return new AuctionReviewTransaction.ApprovedAuction(approved, scheduled, review);
        });

        AuctionReviewTransaction.ApprovedAuction result = service.approve(command("  Looks good  "));

        assertThat(result.item().reviewStatus()).isEqualTo(AuctionItemReviewStatus.APPROVED);
        assertThat(result.session().status()).isEqualTo(AuctionSessionStatus.SCHEDULED);
        ArgumentCaptor<AuctionReview> review = ArgumentCaptor.forClass(AuctionReview.class);
        verify(transaction).approve(review.capture(), eq(item.version()), eq(session));
        assertThat(review.getValue().id()).isEqualTo(301L);
        assertThat(review.getValue().reviewerId()).isEqualTo(REVIEWER_ID);
        assertThat(review.getValue().decision()).isEqualTo(AuctionReviewDecision.APPROVED);
        assertThat(review.getValue().comment()).isEqualTo("Looks good");
        assertThat(review.getValue().reviewedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsAdministratorReviewingOwnAssetBeforeReadingSession() {
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(pendingItem(REVIEWER_ID)));

        assertError(AuctionErrorCode.ASSET_ACCESS_DENIED, () -> service.approve(command(null)));

        verifyNoInteractions(sessionRepository, transaction, idGenerator);
    }

    @Test
    void rejectsStaleSubmissionVersionBeforeReadingSession() {
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(pendingItem()));

        assertError(AuctionErrorCode.SUBMISSION_VERSION_CONFLICT, () -> service.approve(
                new AuctionReviewService.ApproveCommand(REVIEWER_ID, ITEM_ID, 2, null)
        ));

        verifyNoInteractions(sessionRepository, transaction, idGenerator);
    }

    @Test
    void rejectsNonPendingItemAndExpiredStartTime() {
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(approvedItem()));
        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.approve(command(null)));
        verify(sessionRepository, never()).findSessionByItemId(ITEM_ID);

        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(pendingItem()));
        when(sessionRepository.findSessionByItemId(ITEM_ID))
                .thenReturn(Optional.of(draftSession(NOW)));
        assertError(AuctionErrorCode.AUCTION_TIME_INVALID, () -> service.approve(command(null)));
        verifyNoInteractions(transaction, idGenerator);
    }

    @Test
    void mapsConcurrentTransactionFailureToStateConflict() {
        AuctionItem item = pendingItem();
        AuctionSession session = draftSession(NOW.plusSeconds(3600));
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(item));
        when(sessionRepository.findSessionByItemId(ITEM_ID)).thenReturn(Optional.of(session));
        when(idGenerator.nextId()).thenReturn(301L);
        when(transaction.approve(any(), eq(item.version()), eq(session)))
                .thenThrow(new AuctionReviewTransaction.ReviewConflictException("changed concurrently"));

        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.approve(command(null)));
    }

    @Test
    void rejectsInvalidCommandAndOversizedComment() {
        assertError(AuctionErrorCode.ASSET_INVALID, () -> service.approve(null));
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(pendingItem()));
        when(sessionRepository.findSessionByItemId(ITEM_ID))
                .thenReturn(Optional.of(draftSession(NOW.plusSeconds(3600))));

        assertError(AuctionErrorCode.ASSET_INVALID, () -> service.approve(command("x".repeat(501))));
        verifyNoInteractions(transaction, idGenerator);
    }

    private static AuctionReviewService.ApproveCommand command(String comment) {
        return new AuctionReviewService.ApproveCommand(REVIEWER_ID, ITEM_ID, 1, comment);
    }

    private static AuctionItem pendingItem() {
        return pendingItem(SELLER_ID);
    }

    private static AuctionItem pendingItem(long sellerId) {
        return new AuctionItem(
                ITEM_ID, sellerId, "Mechanical keyboard", "A submitted auction item for review",
                "ELECTRONICS", AuctionItemCondition.GOOD, AuctionItemReviewStatus.PENDING_REVIEW,
                1, 2L, NOW.minusSeconds(600), null, NOW.minusSeconds(3600), NOW.minusSeconds(600)
        );
    }

    private static AuctionItem approvedItem() {
        return new AuctionItem(
                ITEM_ID, SELLER_ID, "Mechanical keyboard", "A submitted auction item for review",
                "ELECTRONICS", AuctionItemCondition.GOOD, AuctionItemReviewStatus.APPROVED,
                1, 3L, NOW.minusSeconds(600), NOW, NOW.minusSeconds(3600), NOW
        );
    }

    private static AuctionSession draftSession(Instant startAt) {
        return new AuctionSession(
                201L, ITEM_ID, SELLER_ID,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("50.00"),
                null, null, 0L, startAt, startAt.plusSeconds(3600),
                AuctionSessionStatus.DRAFT, 4L, NOW.minusSeconds(3600), NOW.minusSeconds(600)
        );
    }

    private static AuctionSession scheduledSession() {
        AuctionSession draft = draftSession(NOW.plusSeconds(3600));
        return new AuctionSession(
                draft.id(), draft.itemId(), draft.sellerId(), draft.startPrice(), draft.bidIncrement(),
                draft.depositAmount(), null, null, 0L, draft.startAt(), draft.endAt(),
                AuctionSessionStatus.SCHEDULED, draft.version() + 1, draft.createdAt(), NOW
        );
    }

    private static void assertError(AuctionErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }
}
