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
            return new AuctionReviewTransaction.ReviewedAuction(approved, scheduled, review);
        });

        AuctionReviewTransaction.ReviewedAuction result = service.review(approveCommand("  Looks good  "));

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

        assertError(AuctionErrorCode.ASSET_ACCESS_DENIED, () -> service.review(approveCommand(null)));

        verifyNoInteractions(sessionRepository, transaction, idGenerator);
    }

    @Test
    void rejectsStaleSubmissionVersionBeforeReadingSession() {
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(pendingItem()));

        assertError(AuctionErrorCode.SUBMISSION_VERSION_CONFLICT, () -> service.review(
                new AuctionReviewService.ReviewCommand(
                        REVIEWER_ID, ITEM_ID, 2, AuctionReviewDecision.APPROVED, null
                )
        ));

        verifyNoInteractions(sessionRepository, transaction, idGenerator);
    }

    @Test
    void rejectsNonPendingItemAndExpiredStartTime() {
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(approvedItem()));
        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.review(approveCommand(null)));
        verify(sessionRepository, never()).findSessionByItemId(ITEM_ID);

        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(pendingItem()));
        when(sessionRepository.findSessionByItemId(ITEM_ID))
                .thenReturn(Optional.of(draftSession(NOW)));
        assertError(AuctionErrorCode.AUCTION_TIME_INVALID, () -> service.review(approveCommand(null)));
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

        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.review(approveCommand(null)));
    }

    @Test
    void rejectsInvalidCommandAndOversizedComment() {
        assertError(AuctionErrorCode.ASSET_INVALID, () -> service.review(null));
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(pendingItem()));
        when(sessionRepository.findSessionByItemId(ITEM_ID))
                .thenReturn(Optional.of(draftSession(NOW.plusSeconds(3600))));

        assertError(AuctionErrorCode.ASSET_INVALID, () -> service.review(approveCommand("x".repeat(501))));
        verifyNoInteractions(transaction, idGenerator);
    }

    @Test
    void rejectsCurrentSubmissionWithRequiredTrimmedCommentAndKeepsSessionDraft() {
        AuctionItem item = pendingItem();
        AuctionSession session = draftSession(NOW.minusSeconds(1));
        AuctionItem rejected = rejectedItem();
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(item));
        when(sessionRepository.findSessionByItemId(ITEM_ID)).thenReturn(Optional.of(session));
        when(idGenerator.nextId()).thenReturn(302L);
        when(transaction.reject(any(), eq(item.version()), eq(session))).thenAnswer(invocation -> {
            AuctionReview review = invocation.getArgument(0);
            return new AuctionReviewTransaction.ReviewedAuction(rejected, session, review);
        });

        AuctionReviewTransaction.ReviewedAuction result = service.review(rejectCommand("  Add clearer photos  "));

        assertThat(result.item().reviewStatus()).isEqualTo(AuctionItemReviewStatus.REJECTED);
        assertThat(result.session().status()).isEqualTo(AuctionSessionStatus.DRAFT);
        ArgumentCaptor<AuctionReview> review = ArgumentCaptor.forClass(AuctionReview.class);
        verify(transaction).reject(review.capture(), eq(item.version()), eq(session));
        assertThat(review.getValue().decision()).isEqualTo(AuctionReviewDecision.REJECTED);
        assertThat(review.getValue().comment()).isEqualTo("Add clearer photos");
    }

    @Test
    void requiresRejectionCommentBeforeReadingStateOrGeneratingId() {
        assertError(AuctionErrorCode.ASSET_INVALID, () -> service.review(rejectCommand(null)));
        assertError(AuctionErrorCode.ASSET_INVALID, () -> service.review(rejectCommand("   ")));

        verifyNoInteractions(itemRepository, sessionRepository, transaction, idGenerator);
    }

    @Test
    void mapsConcurrentRejectionFailureToStateConflict() {
        AuctionItem item = pendingItem();
        AuctionSession session = draftSession(NOW.plusSeconds(3600));
        when(itemRepository.findItemById(ITEM_ID)).thenReturn(Optional.of(item));
        when(sessionRepository.findSessionByItemId(ITEM_ID)).thenReturn(Optional.of(session));
        when(idGenerator.nextId()).thenReturn(303L);
        when(transaction.reject(any(), eq(item.version()), eq(session)))
                .thenThrow(new AuctionReviewTransaction.ReviewConflictException("changed concurrently"));

        assertError(AuctionErrorCode.ASSET_STATE_CONFLICT, () -> service.review(rejectCommand("Needs work")));
    }

    private static AuctionReviewService.ReviewCommand approveCommand(String comment) {
        return new AuctionReviewService.ReviewCommand(
                REVIEWER_ID, ITEM_ID, 1, AuctionReviewDecision.APPROVED, comment
        );
    }

    private static AuctionReviewService.ReviewCommand rejectCommand(String comment) {
        return new AuctionReviewService.ReviewCommand(
                REVIEWER_ID, ITEM_ID, 1, AuctionReviewDecision.REJECTED, comment
        );
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

    private static AuctionItem rejectedItem() {
        return new AuctionItem(
                ITEM_ID, SELLER_ID, "Mechanical keyboard", "A submitted auction item for review",
                "ELECTRONICS", AuctionItemCondition.GOOD, AuctionItemReviewStatus.REJECTED,
                1, 3L, NOW.minusSeconds(600), null, NOW.minusSeconds(3600), NOW
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
